/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 capsopasme and Fcitx5 for Android Contributors
 */
package io.github.capsopasme.audioinput.ime

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import io.github.capsopasme.audioinput.R
import io.github.capsopasme.audioinput.asr.AudioCapture
import io.github.capsopasme.audioinput.asr.ModelPaths
import io.github.capsopasme.audioinput.asr.SpeechModel
import io.github.capsopasme.audioinput.asr.VoiceClient
import io.github.capsopasme.audioinput.settings.PermissionActivity
import io.github.capsopasme.audioinput.settings.Prefs
import io.github.capsopasme.audioinput.settings.SettingsActivity

/**
 * One dictation session at a time, driven by the input method's lifecycle. Main thread only.
 *
 * ```
 *  Idle ──start──▶ Loading ──ready──▶ Listening ──stop / hide / silence──▶ Finishing ──done──▶ Idle
 * ```
 *
 * Everything the panel shows is kept here, so a re-created view (rotation, dark mode) shows the
 * same state. Recording happens in this process (it has the microphone while the keyboard is
 * visible), recognition in the `:voice` process.
 */
class VoiceController(private val service: VoiceImeService) : VoicePanel.Actions {

    private enum class State { Idle, Loading, Listening, Finishing }

    private val ctx: Context = service
    private val prefs = Prefs.get(service)
    private val main = Handler(Looper.getMainLooper())

    private var state = State.Idle
    private var panel: VoicePanel? = null

    /** the input window is on screen */
    private var visible = false

    /**
     * Start listening at the next onStartInputView. Set when the window was hidden or the editor
     * changed: the keyboard appearing is the user asking to dictate. Not set when an app merely
     * restarts input on the same field (some do after every commit), so stopping the microphone
     * by hand is never undone behind the user's back.
     */
    private var autoStartPending = true

    /** start a new session as soon as the finishing one is done */
    private var startWhenIdle = false

    // --- what the panel shows ---
    private var status: CharSequence = ""
    private var action: Pair<CharSequence, () -> Unit>? = null
    private val committed = StringBuilder()
    private var partial = ""
    private var enterIcon = R.drawable.ic_return
    private var enterDesc = R.string.panel_enter
    private var passwordField = false

    /** editor ([VoiceImeService.editorGeneration]) the transcript and undo history belong to */
    private var transcriptEditor = -1

    /** editor that was being edited when listening started; results for another one are dropped */
    private var sessionEditor = -1
    private var droppedResult = false
    private var silentInput = false
    private var backendLabel = ""

    /** texts committed by recognition into the current editor, newest last */
    private val undoStack = ArrayDeque<String>()

    /** model of the current / last session; the setting may change while it runs */
    private var sessionModel: SpeechModel = prefs.model

    @Volatile
    private var shownLevel = -1

    private val clientListener: VoiceClient.Listener = object : VoiceClient.Listener {
        override fun onLoading() {
            // Only while still waiting to listen. Stopped before the recognizer even got to the
            // session (Finishing), the load is just part of finishing: going back to Loading here
            // would let the following onReady show "listening" with the microphone already off.
            if (state != State.Loading) return
            status = ctx.getString(R.string.status_loading, ctx.getString(sessionModel.titleRes))
            render()
        }

        override fun onReady(backend: String, fallback: Boolean, loadMillis: Long) {
            if (state != State.Loading && state != State.Listening) return
            main.removeCallbacks(watchdog)
            state = State.Listening
            backendLabel = backend
            // levels measured while loading weren't shown, let the next chunk update the halo
            shownLevel = -1
            armListenBackstop()
            updateListeningStatus()
            render()
        }

        override fun onPartial(text: String) {
            if (state != State.Listening && state != State.Finishing) return
            armListenBackstop()
            partial = text
            renderTranscript()
        }

        override fun onFinal(text: String) {
            partial = ""
            NpuHealth.crashesInARow = 0
            if (state == State.Listening) armListenBackstop()
            val ic = service.currentInputConnection
            if (service.editorGeneration != sessionEditor || ic == null) {
                // Focus moved to another field / app while the last sentence was still being
                // decoded. Committing now would put it into the wrong editor - possibly a
                // password field, where dictation is never offered.
                Log.i(TAG, "Dropping a result: the editor changed")
                droppedResult = true
                status = ctx.getString(R.string.status_dropped_editor_changed)
                render()
                return
            }
            commitRecognized(ic, text)
            render()
        }

        override fun onError(message: String) {
            endSession()
            status = ctx.getString(R.string.status_error, message)
            offerSettings()
            render()
            if (!visible) scheduleIdleUnbind()
        }

        override fun onDone(autoStopped: Boolean) {
            endSession()
            // keep the "not inserted" notice visible instead of replacing it
            if (!droppedResult) {
                status = ctx.getString(if (autoStopped) R.string.status_auto_stopped else R.string.status_idle)
            }
            render()
            if (startWhenIdle && visible && !passwordField) {
                startWhenIdle = false
                startListening()
            } else if (!visible) {
                scheduleIdleUnbind()
            }
        }

        override fun onServiceCrashed() {
            // the client has already dropped its binding
            endSession()
            // A model that crashes while recognizing would otherwise kill every session: after
            // the second crash in a row use the CPU model (see NpuHealth).
            NpuHealth.crashesInARow++
            if (NpuHealth.crashesInARow >= 2 && sessionModel.isQnn && !NpuHealth.avoidQnn) {
                NpuHealth.avoidQnn = true
                status = ctx.getString(R.string.status_crashed_avoid_npu)
            } else {
                status = ctx.getString(R.string.status_crashed)
            }
            offerSettings()
            render()
        }
    }

    private val client: VoiceClient = VoiceClient(ctx, clientListener)

    private val capture: AudioCapture = AudioCapture(object : AudioCapture.Listener {
        override fun onAudio(pcm: ShortArray, rms: Float, token: Int) {
            // one main thread hop per chunk (10/s) for both: audio has to be queued in order with
            // MSG_START / MSG_STOP anyway, see VoiceClient.sendAudio
            val level = VoicePanel.levelStep(rms)
            val levelChanged = level != shownLevel
            shownLevel = level
            main.post {
                client.sendAudio(pcm, token)
                if (levelChanged && state == State.Listening) panel?.setLevelStep(level)
            }
        }

        override fun onCaptureFailed(e: Throwable, token: Int) {
            main.post {
                if (token != client.currentSession) return@post
                // microphone taken by a call, or the permission was revoked
                client.cancel()
                endSession()
                status = ctx.getString(R.string.status_mic_error, e.localizedMessage ?: e.javaClass.simpleName)
                render()
            }
        }

        override fun onSilentInput(silent: Boolean, token: Int) {
            main.post {
                if (token != client.currentSession) return@post
                silentInput = silent
                if (state == State.Listening) {
                    updateListeningStatus()
                    render()
                }
            }
        }
    })

    private val focus: AudioFocusHolder = AudioFocusHolder(ctx)

    // --- lifecycle, called by VoiceImeService ----------------------------------------------------

    fun attach(panel: VoicePanel) {
        this.panel = panel
        panel.actions = this
        render()
    }

    fun onStartInputView(info: EditorInfo) {
        main.removeCallbacks(idleUnbind)
        updateEnterKey(info)
        val editor = service.editorGeneration
        if (editor != transcriptEditor) {
            transcriptEditor = editor
            committed.setLength(0)
            partial = ""
            undoStack.clear()
            autoStartPending = true
        }
        passwordField = isPassword(info)
        val autoStart = autoStartPending && prefs.autoStart
        autoStartPending = false
        if (passwordField) {
            if (state == State.Loading || state == State.Listening) stopListening()
            startWhenIdle = false
            status = ctx.getString(R.string.status_password_field)
            action = ctx.getString(R.string.action_switch_back) to { switchBack() }
            render()
            return
        }
        // e.g. the password field's "back to the keyboard", from the field before this one
        action = null
        when (state) {
            State.Loading, State.Listening -> {
                // the same editor restarted (or the view was re-created): keep going
                if (sessionEditor == editor) {
                    render()
                    return
                }
                stopListening()
                startWhenIdle = autoStart
            }
            State.Finishing -> {
                startWhenIdle = autoStart
                // replace e.g. the password field's notice, but keep the "not inserted" one
                if (!droppedResult) status = ctx.getString(R.string.status_finishing)
            }
            State.Idle -> {
                if (checkReady()) {
                    if (autoStart) {
                        startListening()
                        return
                    }
                    if (!droppedResult) status = ctx.getString(R.string.status_idle)
                    preload()
                }
            }
        }
        render()
    }

    fun onFinishInputView() {
        if (state == State.Loading || state == State.Listening) stopListening()
        startWhenIdle = false
    }

    fun onWindowShown() {
        visible = true
        main.removeCallbacks(idleUnbind)
    }

    fun onWindowHidden() {
        visible = false
        autoStartPending = true
        startWhenIdle = false
        // the keyboard was hidden while listening: release the microphone
        if (state == State.Loading || state == State.Listening) stopListening()
        // don't keep the recognizer process (and its model) pinned while nobody types
        scheduleIdleUnbind()
    }

    fun destroy() {
        main.removeCallbacksAndMessages(null)
        capture.stop()
        focus.abandon()
        client.cancel()
        client.unbind()
        panel = null
    }

    // --- session ---------------------------------------------------------------------------------

    private fun checkReady(): Boolean {
        if (!AudioCapture.hasPermission(ctx)) {
            status = ctx.getString(R.string.status_need_permission)
            action = ctx.getString(R.string.action_grant_permission) to { PermissionActivity.launch(ctx) }
            return false
        }
        val model = prefs.model
        if (!ModelPaths.isInstalled(ctx, model)) {
            status = ctx.getString(R.string.status_need_model, ctx.getString(model.titleRes))
            action = ctx.getString(R.string.action_download_model) to { openSettings() }
            return false
        }
        return true
    }

    private fun preload() {
        if (state != State.Idle) return
        client.preload(prefs.model, prefs.language, prefs.itn, prefs.silenceMs, NpuHealth.avoidQnn)
    }

    private fun startListening() {
        if (state != State.Idle || passwordField) return
        if (!checkReady()) {
            render()
            return
        }
        main.removeCallbacks(idleUnbind)
        action = null
        partial = ""
        sessionEditor = service.editorGeneration
        droppedResult = false
        silentInput = false
        state = State.Loading
        status = ctx.getString(R.string.status_starting)
        val model = prefs.model
        sessionModel = model
        val session = client.start(
            model = model,
            language = prefs.language,
            itn = prefs.itn,
            partial = prefs.partial,
            silenceMs = prefs.silenceMs,
            idleStopMs = prefs.idleStopSeconds * 1000,
            avoidQnn = NpuHealth.avoidQnn,
        )
        // the recognizer can't be reached: its error is already on the way
        if (session == 0) {
            render()
            return
        }
        shownLevel = -1
        if (prefs.pauseMedia) focus.request()
        // audio recorded while the model loads is queued, nothing gets lost
        if (!capture.start(ctx, session)) {
            client.cancel()
            endSession()
            checkReady()
            render()
            return
        }
        armWatchdog(model.loadTimeoutSeconds)
        render()
    }

    private fun stopListening() {
        val id = client.currentSession
        // send MSG_STOP only after the capture thread has delivered its last chunk,
        // otherwise the end of the last word can be cut off
        val pending = capture.stop { main.post { client.stop(id) } }
        focus.abandon()
        main.removeCallbacks(listenBackstop)
        if (client.isSessionActive) {
            // Stopped before the model finished loading (e.g. the keyboard was hidden right
            // away): the load still has to finish before the last sentence can be decoded, so
            // keep its full time. A shorter deadline would kill a slow but healthy first load,
            // and an interrupted NPU load counts as a crash.
            val seconds = sessionModel.finishTimeoutSeconds +
                    if (state == State.Loading) sessionModel.loadTimeoutSeconds else 0
            state = State.Finishing
            status = ctx.getString(R.string.status_finishing)
            if (!pending) client.stop(id)
            armWatchdog(seconds)
        } else {
            endSession()
        }
        render()
    }

    /** back to Idle; the microphone is released whatever happened */
    private fun endSession() {
        capture.stop()
        focus.abandon()
        main.removeCallbacks(watchdog)
        main.removeCallbacks(listenBackstop)
        state = State.Idle
        partial = ""
        silentInput = false
        if (switchAfterFinish) {
            switchAfterFinish = false
            main.removeCallbacks(switchTimeout)
            // after the caller has finished updating the state
            main.post { doSwitchBack() }
        }
    }

    /**
     * The recognizer didn't finish loading / didn't answer MSG_STOP in time: a native call that
     * never returns (a wedged DSP). Kill it; the next session starts a fresh process.
     */
    private val watchdog = Runnable {
        if (state != State.Loading && state != State.Finishing) return@Runnable
        Log.w(TAG, "Recognizer unresponsive in state $state")
        client.killService()
        endSession()
        status = ctx.getString(R.string.status_unresponsive)
        offerSettings()
        render()
    }

    private fun armWatchdog(seconds: Int) {
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, seconds * 1000L)
    }

    /**
     * Battery backstop: the recognizer stops the session itself after a stretch of silence and
     * reports every sentence, so a session that has been quiet for much longer than that has
     * lost its recognizer. Release the microphone.
     */
    private val listenBackstop = Runnable {
        if (state == State.Listening) {
            Log.w(TAG, "No events from the recognizer for too long, stopping")
            stopListening()
        }
    }

    private fun armListenBackstop() {
        main.removeCallbacks(listenBackstop)
        main.postDelayed(listenBackstop, (prefs.idleStopSeconds + 40) * 1000L)
    }

    /**
     * Unbind shortly after the keyboard is hidden. While bound (BIND_IMPORTANT) the `:voice`
     * process runs at the keyboard's priority and can't be reclaimed; once unbound it becomes a
     * cached process that the system freezes, keeping the model loaded for free until memory is
     * needed. The delay just coalesces quick hide/show flickers.
     */
    private val idleUnbind: Runnable = object : Runnable {
        override fun run() {
            if (visible) return
            if (state == State.Loading || state == State.Listening) return
            if (state == State.Finishing) {
                // the final decode is still running, unbinding now would throw its result away
                main.postDelayed(this, IDLE_UNBIND_DELAY_MS)
                return
            }
            client.unbind()
        }
    }

    private fun scheduleIdleUnbind() {
        main.removeCallbacks(idleUnbind)
        main.postDelayed(idleUnbind, IDLE_UNBIND_DELAY_MS)
    }

    private fun updateListeningStatus() {
        status = if (silentInput) ctx.getString(R.string.status_silent_input)
        else ctx.getString(R.string.status_listening, backendLabel)
    }

    // --- text ------------------------------------------------------------------------------------

    private fun isAsciiWordChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    /** the panel shows only the tail of the transcript, older text needn't stay in memory */
    private fun trimTranscript() {
        if (committed.length > MAX_TRANSCRIPT) committed.delete(0, committed.length - MAX_TRANSCRIPT / 2)
    }

    private fun commitRecognized(ic: InputConnection, raw: String) {
        var text = raw.trim()
        if (prefs.dropTrailingPeriod) text = text.trimEnd('。', '.', '．')
        if (text.isEmpty()) return
        if (prefs.autoSpace && isAsciiWordChar(text.first())) {
            val prev = ic.getTextBeforeCursor(1, 0)?.lastOrNull()
            if (prev != null && (isAsciiWordChar(prev) || prev in ".,!?;:")) text = " $text"
        }
        if (!ic.commitText(text, 1)) {
            // the editor's connection is gone (it lost focus a moment ago)
            droppedResult = true
            status = ctx.getString(R.string.status_dropped_editor_changed)
            return
        }
        committed.append(text)
        trimTranscript()
        undoStack.addLast(text)
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
    }

    private fun commitTyped(text: String) {
        val ic = service.currentInputConnection ?: return
        ic.commitText(text, 1)
        committed.append(text)
        trimTranscript()
        renderTranscript()
    }

    private fun dropLastShownCodePoint() {
        val end = committed.length
        if (end == 0) return
        // drop a whole code point, never half of a surrogate pair
        val start = if (end >= 2 && Character.isLowSurrogate(committed[end - 1]) &&
            Character.isHighSurrogate(committed[end - 2])
        ) end - 2 else end - 1
        committed.setLength(start)
    }

    private fun updateEnterKey(info: EditorInfo) {
        val (icon, desc) = when (enterAction(info)) {
            EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_search to R.string.panel_enter_search
            EditorInfo.IME_ACTION_SEND -> R.drawable.ic_send to R.string.panel_enter_send
            EditorInfo.IME_ACTION_GO -> R.drawable.ic_arrow_forward to R.string.panel_enter_go
            EditorInfo.IME_ACTION_NEXT -> R.drawable.ic_arrow_forward to R.string.panel_enter_next
            EditorInfo.IME_ACTION_DONE -> R.drawable.ic_done to R.string.panel_enter_done
            else -> R.drawable.ic_return to R.string.panel_enter
        }
        enterIcon = icon
        enterDesc = desc
    }

    // --- panel actions ---------------------------------------------------------------------------

    private fun haptic() {
        if (prefs.haptics) panel?.performHaptic()
    }

    override fun onMic() {
        haptic()
        when (state) {
            State.Idle -> startListening()
            State.Loading, State.Listening -> {
                startWhenIdle = false
                stopListening()
            }
            State.Finishing -> {}
        }
    }

    override fun onBackspace() {
        // the editor deletes the selection if there is one, or the character before the cursor,
        // and handles emoji / grapheme clusters itself
        if (service.currentInputConnection == null) return
        service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        dropLastShownCodePoint()
        renderTranscript()
    }

    override fun onUndo() {
        haptic()
        val last = undoStack.lastOrNull() ?: return
        val ic = service.currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(last.length, 0)?.toString()
        val selected = ic.getSelectedText(0)
        if (before == last && selected.isNullOrEmpty()) {
            ic.deleteSurroundingText(last.length, 0)
            undoStack.removeLast()
            if (committed.endsWith(last)) committed.setLength(committed.length - last.length)
        } else {
            // the cursor moved or the text was edited since: deleting by length would hit
            // something else
            undoStack.clear()
            status = ctx.getString(R.string.status_cannot_undo)
        }
        render()
    }

    override fun onSpace() = commitTyped(" ")

    override fun onPunctuation(text: String) = commitTyped(text)

    override fun onEnter() {
        val ei = service.currentInputEditorInfo ?: return
        val ic = service.currentInputConnection ?: return
        val a = enterAction(ei)
        if (a != null) ic.performEditorAction(a) else service.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    override fun onSwitchIme() {
        haptic()
        switchBack()
    }

    override fun onSwitchImeLongPress() {
        haptic()
        showImePicker()
    }

    override fun onSettings() = openSettings()

    override fun onAction() {
        action?.second?.invoke()
    }

    /** switch input method once the finishing session has delivered its last sentence */
    private var switchAfterFinish = false

    private val switchTimeout = Runnable {
        if (switchAfterFinish) {
            switchAfterFinish = false
            doSwitchBack()
        }
    }

    private fun switchBack() {
        // leaving: a restart queued for another field must not open the microphone on the way out
        startWhenIdle = false
        if (state == State.Finishing && switchAfterFinish) {
            // tapped again while waiting: the user would rather not wait
            switchAfterFinish = false
            main.removeCallbacks(switchTimeout)
            doSwitchBack()
            return
        }
        if (state == State.Loading || state == State.Listening) stopListening()
        if (state == State.Finishing) {
            // Switching away destroys this input method right away, which would throw away the
            // sentence still being decoded. Let it land in the editor first (a slow model needs
            // longer for a long sentence; tapping again switches at once).
            switchAfterFinish = true
            main.removeCallbacks(switchTimeout)
            main.postDelayed(
                switchTimeout,
                if (sessionModel.fastEnoughForPartial) SWITCH_WAIT_MS else SWITCH_WAIT_SLOW_MS
            )
            return
        }
        doSwitchBack()
    }

    private fun doSwitchBack() {
        val switched = try {
            service.switchToPreviousInputMethod() || service.switchToNextInputMethod(false)
        } catch (e: Exception) {
            Log.w(TAG, "switching input method failed", e)
            false
        }
        if (!switched) showImePicker()
    }

    private fun showImePicker() {
        ctx.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    private fun openSettings() {
        if (state == State.Loading || state == State.Listening) stopListening()
        service.requestHideSelf(0)
        ctx.startActivity(
            Intent(ctx, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    private fun offerSettings() {
        action = ctx.getString(R.string.action_open_settings) to { openSettings() }
    }

    // --- rendering -------------------------------------------------------------------------------

    private fun render() {
        val p = panel ?: return
        p.setStatus(status)
        p.setAction(action?.first)
        p.setMicState(
            when (state) {
                State.Idle -> VoicePanel.MicState.Idle
                State.Listening -> VoicePanel.MicState.Listening
                State.Loading, State.Finishing -> VoicePanel.MicState.Busy
            },
            enabled = !passwordField
        )
        p.setUndoEnabled(undoStack.isNotEmpty())
        p.setEnterIcon(enterIcon, ctx.getString(enterDesc))
        p.showTranscript(committed, partial)
    }

    private fun renderTranscript() {
        val p = panel ?: return
        p.showTranscript(committed, partial)
        p.setUndoEnabled(undoStack.isNotEmpty())
    }

    companion object {
        private const val TAG = "VoiceController"
        private const val IDLE_UNBIND_DELAY_MS = 10_000L
        private const val MAX_UNDO = 20
        private const val MAX_TRANSCRIPT = 4_000
        private const val SWITCH_WAIT_MS = 4_000L
        private const val SWITCH_WAIT_SLOW_MS = 12_000L

        fun isPassword(info: EditorInfo): Boolean {
            val cls = info.inputType and EditorInfo.TYPE_MASK_CLASS
            val variation = info.inputType and EditorInfo.TYPE_MASK_VARIATION
            return when (cls) {
                EditorInfo.TYPE_CLASS_TEXT -> variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
                        variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                        variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD
                EditorInfo.TYPE_CLASS_NUMBER -> variation == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
                else -> false
            }
        }

        /** the editor action the return key performs, null for a plain Enter (new line) */
        fun enterAction(info: EditorInfo): Int? {
            if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
            return when (val a = info.imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND,
                EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_PREVIOUS -> a
                else -> null
            }
        }
    }
}
