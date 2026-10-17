/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 capsopasme
 */
package io.github.capsopasme.audioinput.asr

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import io.github.capsopasme.audioinput.ime.NpuHealth
import io.github.capsopasme.audioinput.settings.Prefs
import io.github.capsopasme.audioinput.asr.VoiceProtocol as P

/**
 * Lets the companion assistant app (capsopasme/Assistant) use this app's recognizer without the
 * keyboard: the assistant records the microphone itself and streams 16 kHz PCM here; this service
 * forwards it to the `:voice` recognizer with the model and options chosen in this app's settings
 * (including the NPU crash fallback), and relays the results.
 *
 * Same messages as [VoiceProtocol]; MSG_START needs no model keys (they come from the settings),
 * KEY_PARTIAL / KEY_SILENCE_MS / KEY_IDLE_STOP_MS are optional overrides.
 *
 * While a client stays bound, this process and the `:voice` process stay bound too, so the loaded
 * model stays in memory: no reload (seconds of CPU / NPU work) for each dictation. Only allowed
 * callers ([ALLOWED]) are served.
 */
class RecognizerBridgeService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var client: VoiceClient

    /** the external session currently forwarded: its reply target and its own id */
    private var replyTo: Messenger? = null
    private var externalId = 0
    private var internalId = 0
    private var sessionModel: SpeechModel? = null
    private var allowedUid = -1

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { msg ->
        handle(msg)
        true
    })

    private val listener = object : VoiceClient.Listener {
        override fun onLoading() = forward(P.EVT_LOADING)

        override fun onReady(backend: String, fallback: Boolean, loadMillis: Long) {
            main.removeCallbacks(watchdog)
            forward(P.EVT_READY) {
                putString(P.KEY_BACKEND, backend)
                putBoolean(P.KEY_FALLBACK, fallback)
                putLong(P.KEY_LOAD_MS, loadMillis)
            }
        }

        override fun onPartial(text: String) = forward(P.EVT_PARTIAL) { putString(P.KEY_TEXT, text) }

        override fun onFinal(text: String) {
            NpuHealth.crashesInARow = 0
            forward(P.EVT_FINAL) { putString(P.KEY_TEXT, text) }
        }

        override fun onError(message: String) {
            forward(P.EVT_ERROR) { putString(P.KEY_MESSAGE, message) }
            endSession()
        }

        override fun onDone(autoStopped: Boolean) {
            forward(P.EVT_DONE) { if (autoStopped) putBoolean(P.KEY_AUTO_STOPPED, true) }
            endSession()
        }

        override fun onServiceCrashed() {
            // same policy as the keyboard: the second crash in a row switches to the CPU model
            NpuHealth.crashesInARow++
            val model = sessionModel
            val avoid = NpuHealth.crashesInARow >= 2 && model?.isQnn == true && !NpuHealth.avoidQnn
            if (avoid) NpuHealth.avoidQnn = true
            forward(P.EVT_ERROR) {
                putString(P.KEY_MESSAGE, if (avoid) "识别进程崩溃，已改用 CPU 模型" else "识别进程崩溃，请重试")
            }
            endSession()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.get(this)
        client = VoiceClient(this, listener)
    }

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onUnbind(intent: Intent?): Boolean {
        if (internalId != 0) client.cancel()
        endSession()
        client.unbind()
        return false
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        client.unbind()
        super.onDestroy()
    }

    private fun allowed(uid: Int): Boolean {
        if (uid == allowedUid) return true
        val ok = packageManager.getPackagesForUid(uid)?.any { it in ALLOWED } == true
        if (ok) allowedUid = uid
        return ok
    }

    private fun handle(msg: Message) {
        if (!allowed(msg.sendingUid)) {
            Log.w(TAG, "refused a request from uid ${msg.sendingUid}")
            reply(msg.replyTo, P.EVT_ERROR, msg.arg1) { putString(P.KEY_MESSAGE, "not allowed") }
            return
        }
        val d = msg.data
        when (msg.what) {
            P.MSG_PRELOAD -> client.preload(prefs.model, prefs.language, prefs.itn, prefs.silenceMs, NpuHealth.avoidQnn)
            P.MSG_START -> start(msg, d)
            P.MSG_AUDIO -> {
                if (msg.arg1 != externalId || internalId == 0) return
                val pcm = d.getShortArray(P.KEY_PCM16) ?: return
                client.sendAudio(pcm, internalId)
            }
            P.MSG_STOP -> if (msg.arg1 == externalId && internalId != 0) {
                client.stop(internalId)
                armWatchdog(FINISH_TIMEOUT_S)
            }
            P.MSG_CANCEL -> if (msg.arg1 == externalId && internalId != 0) {
                client.cancel()
                endSession()
            }
        }
    }

    private fun start(msg: Message, d: Bundle) {
        // a new session replaces an unfinished one
        if (internalId != 0) {
            forward(P.EVT_ERROR) { putString(P.KEY_MESSAGE, "interrupted") }
            client.cancel()
            endSession()
        }
        val model = prefs.model
        replyTo = msg.replyTo
        externalId = msg.arg1
        sessionModel = model
        internalId = client.start(
            model = model,
            language = prefs.language,
            itn = prefs.itn,
            partial = if (d.containsKey(P.KEY_PARTIAL)) d.getBoolean(P.KEY_PARTIAL) else prefs.partial,
            silenceMs = d.getInt(P.KEY_SILENCE_MS, prefs.silenceMs).coerceIn(200, 2000),
            idleStopMs = d.getInt(P.KEY_IDLE_STOP_MS, prefs.idleStopSeconds * 1000).coerceAtLeast(0),
            avoidQnn = NpuHealth.avoidQnn,
        )
        if (internalId == 0) {
            forward(P.EVT_ERROR) { putString(P.KEY_MESSAGE, "识别服务无法启动") }
            endSession()
            return
        }
        armWatchdog(model.loadTimeoutSeconds)
    }

    /** No answer while loading or finishing: a wedged DSP. Kill the recognizer, report it. */
    private val watchdog = Runnable {
        if (internalId == 0) return@Runnable
        Log.w(TAG, "recognizer unresponsive, killing it")
        client.killService()
        forward(P.EVT_ERROR) { putString(P.KEY_MESSAGE, "识别服务无响应，已重置") }
        endSession()
    }

    private fun armWatchdog(seconds: Int) {
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, seconds * 1000L)
    }

    private fun endSession() {
        main.removeCallbacks(watchdog)
        internalId = 0
        externalId = 0
        replyTo = null
        sessionModel = null
    }

    private fun forward(what: Int, fill: Bundle.() -> Unit = {}) {
        if (externalId == 0) return
        reply(replyTo, what, externalId, fill)
    }

    private fun reply(to: Messenger?, what: Int, id: Int, fill: Bundle.() -> Unit = {}) {
        to ?: return
        val m = Message.obtain(null, what, id, 0)
        m.data = Bundle().apply(fill)
        try {
            to.send(m)
        } catch (_: RemoteException) {
            // the assistant went away: stop recognizing for it
            if (internalId != 0) client.cancel()
            endSession()
        }
    }

    companion object {
        private const val TAG = "RecognizerBridge"
        private const val FINISH_TIMEOUT_S = 20

        /** packages allowed to use the recognizer through this service */
        private val ALLOWED = setOf("io.github.capsopasme.assistant", "io.github.capsopasme.assistant.debug")
    }
}
