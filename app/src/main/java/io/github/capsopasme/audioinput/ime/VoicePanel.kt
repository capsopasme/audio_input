/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 capsopasme and Fcitx5 for Android Contributors
 */
package io.github.capsopasme.audioinput.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.WindowInsetsCompat
import io.github.capsopasme.audioinput.R
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

/**
 * The voice keyboard, built with plain framework views (fast to create, nothing to inflate):
 *
 * ```
 *  [⌨]          status line            [⚙]
 *  ┌────────────── live transcript ──────────────┐
 *  [ ，][ 。][ ？][ ！][ 、][ ：]
 *  [↶] [⌫]         ( 🎤 )         [␣] [↵]
 * ```
 */
@SuppressLint("ViewConstructor")
class VoicePanel(
    private val ctx: Context,
    heightDp: Int,
    showPunctuation: Boolean,
) {

    enum class MicState { Idle, Busy, Listening }

    interface Actions {
        fun onMic()
        fun onBackspace()
        fun onUndo()
        fun onSpace()
        fun onEnter()
        fun onPunctuation(text: String)
        fun onSwitchIme()
        fun onSwitchImeLongPress()
        fun onSettings()
        fun onAction()
    }

    var actions: Actions? = null

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    private fun color(id: Int) = ctx.getColor(id)

    private val textPrimary = color(R.color.panel_text)
    private val textSecondary = color(R.color.panel_text_secondary)
    private val accent = color(R.color.panel_accent)
    private val onAccent = color(R.color.panel_on_accent)
    private val keyBg = color(R.color.panel_key)
    private val ripple = ColorStateList.valueOf(color(R.color.panel_ripple))

    private fun roundRect(fill: Int, radiusDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
    }

    private fun oval(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    private fun iconButton(@DrawableRes icon: Int, desc: Int, filled: Boolean) = ImageView(ctx).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(if (filled) textPrimary else textSecondary)
        scaleType = ImageView.ScaleType.CENTER
        background = if (filled) {
            val bg = oval(keyBg)
            RippleDrawable(ripple, bg, bg)
        } else {
            RippleDrawable(ripple, null, oval(keyBg))
        }
        isClickable = true
        isFocusable = false
        contentDescription = ctx.getString(desc)
    }

    // --- top bar ---------------------------------------------------------------------------------

    val switchImeButton = iconButton(R.drawable.ic_keyboard, R.string.panel_switch_ime, filled = false).apply {
        setOnClickListener { actions?.onSwitchIme() }
        setOnLongClickListener { actions?.onSwitchImeLongPress(); true }
    }

    private val settingsButton = iconButton(R.drawable.ic_settings, R.string.panel_settings, filled = false).apply {
        setOnClickListener { actions?.onSettings() }
    }

    private val statusText = TextView(ctx).apply {
        setTextColor(textSecondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    private val topBar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(2), dp(4), 0)
        addView(switchImeButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(statusText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(4), 0, dp(4), 0)
        })
        addView(settingsButton, LinearLayout.LayoutParams(dp(40), dp(40)))
    }

    // --- transcript ------------------------------------------------------------------------------

    private val transcriptText = TextView(ctx).apply {
        setTextColor(textPrimary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(2), dp(16), dp(2))
    }

    private val transcriptScroll = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(transcriptText, FrameLayout.LayoutParams(-1, -2))
    }

    /** an extra action shown when something needs fixing (permission / model / password field) */
    private val actionButton = TextView(ctx).apply {
        setTextColor(accent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(6), dp(16), dp(6))
        background = RippleDrawable(ripple, null, roundRect(keyBg, 16))
        isClickable = true
        visibility = View.GONE
        setOnClickListener { actions?.onAction() }
    }

    // --- punctuation row -------------------------------------------------------------------------

    private val punctuationRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(8), dp(2), dp(8), dp(2))
        for (p in PUNCTUATION) {
            val key = TextView(ctx).apply {
                text = p
                setTextColor(textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                gravity = Gravity.CENTER
                val bg = roundRect(keyBg, 8)
                background = RippleDrawable(ripple, bg, bg)
                isClickable = true
                setOnClickListener { actions?.onPunctuation(p) }
            }
            addView(key, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        visibility = if (showPunctuation) View.VISIBLE else View.GONE
    }

    // --- controls --------------------------------------------------------------------------------

    private val halo = View(ctx).apply {
        background = oval(accent)
        alpha = 0.22f
        scaleX = HALO_MIN
        scaleY = HALO_MIN
    }

    private val micIcon = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_mic)
        imageTintList = ColorStateList.valueOf(onAccent)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private val micButton = FrameLayout(ctx).apply {
        val circle = oval(accent)
        background = RippleDrawable(ripple, circle, circle)
        isClickable = true
        contentDescription = ctx.getString(R.string.panel_start)
        addView(micIcon, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        setOnClickListener { actions?.onMic() }
    }

    private val micContainer = FrameLayout(ctx).apply {
        clipChildren = false
        addView(halo, FrameLayout.LayoutParams(dp(88), dp(88), Gravity.CENTER))
        addView(micButton, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
    }

    private val undoButton = iconButton(R.drawable.ic_undo, R.string.panel_undo, filled = true).apply {
        setOnClickListener { actions?.onUndo() }
    }

    private val backspaceButton = iconButton(R.drawable.ic_backspace, R.string.panel_backspace, filled = true).apply {
        setRepeating(this) { actions?.onBackspace() }
    }

    private val spaceButton = iconButton(R.drawable.ic_space, R.string.panel_space, filled = true).apply {
        setOnClickListener { actions?.onSpace() }
    }

    private val enterButton = iconButton(R.drawable.ic_return, R.string.panel_enter, filled = true).apply {
        setOnClickListener { actions?.onEnter() }
    }

    private val controls = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        setPadding(dp(16), 0, dp(16), 0)
        val side = LinearLayout.LayoutParams(dp(48), dp(48))
        addView(undoButton, LinearLayout.LayoutParams(side).apply { marginEnd = dp(10) })
        addView(backspaceButton, LinearLayout.LayoutParams(side))
        addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        addView(micContainer, LinearLayout.LayoutParams(dp(88), dp(88)))
        addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        addView(spaceButton, LinearLayout.LayoutParams(side).apply { marginEnd = dp(10) })
        addView(enterButton, LinearLayout.LayoutParams(side))
    }

    private val content = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        addView(topBar, LinearLayout.LayoutParams(-1, -2))
        addView(transcriptScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(actionButton, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(4)
        })
        addView(punctuationRow, LinearLayout.LayoutParams(-1, dp(40)))
        addView(controls, LinearLayout.LayoutParams(-1, dp(92)))
    }

    val root: View = object : LinearLayout(ctx) {
        override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
            // Since Android 15 the input window is laid out behind the navigation bar (and
            // fcitx found some ROMs report it oddly with gesture navigation): keep the controls
            // above it and let the background run underneath.
            setPadding(0, 0, 0, navBarBottomInset(insets))
            return insets
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            // on API 35+ insets are not dispatched again to a replaced input view by themselves
            requestApplyInsets()
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(color(R.color.panel_background))
        clipChildren = false
        addView(content, LinearLayout.LayoutParams(-1, dp(heightDp)))
    }

    private fun navBarBottomInset(windowInsets: WindowInsets): Int {
        val insets = WindowInsetsCompat.toWindowInsetsCompat(windowInsets)
        val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        val mandatory = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom
        var bottom = max(navBars, mandatory)
        if (bottom <= 0) {
            val gestures = insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
            if (gestures > 0) bottom = max(gestures, navigationBarFrameHeight())
        }
        return bottom
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun navigationBarFrameHeight(): Int {
        val res = Resources.getSystem()
        val id = res.getIdentifier("navigation_bar_frame_height", "dimen", "android")
        return if (id != 0) res.getDimensionPixelSize(id) else 0
    }

    // --- state -----------------------------------------------------------------------------------

    fun setStatus(text: CharSequence) {
        if (statusText.text.toString() != text.toString()) statusText.text = text
    }

    fun setAction(text: CharSequence?) {
        if (text == null) {
            actionButton.visibility = View.GONE
        } else {
            actionButton.text = text
            actionButton.visibility = View.VISIBLE
        }
    }

    private var shownMicState: MicState? = null

    fun setMicState(state: MicState, enabled: Boolean) {
        if (state != shownMicState) {
            shownMicState = state
            micIcon.setImageResource(if (state == MicState.Idle) R.drawable.ic_mic else R.drawable.ic_stop)
            micButton.contentDescription = ctx.getString(
                if (state == MicState.Idle) R.string.panel_start else R.string.panel_stop
            )
        }
        micButton.isEnabled = enabled
        micButton.alpha = when {
            !enabled -> 0.35f
            state == MicState.Busy -> 0.55f
            else -> 1f
        }
        if (state != MicState.Listening) setLevelStep(0)
    }

    fun setUndoEnabled(enabled: Boolean) {
        undoButton.isEnabled = enabled
        undoButton.alpha = if (enabled) 1f else 0.4f
    }

    fun setEnterIcon(@DrawableRes icon: Int, desc: CharSequence) {
        enterButton.setImageResource(icon)
        enterButton.contentDescription = desc
    }

    /**
     * Show the microphone level as one of a few halo sizes. Set directly instead of animated:
     * an animation per audio chunk (10/s) keeps the panel redrawing at the display's full refresh
     * rate for as long as the microphone is open, even in silence. This way a frame is drawn
     * only when the level actually moves to another step.
     */
    fun setLevelStep(step: Int) {
        val v = HALO_MIN + (1f - HALO_MIN) * step.coerceIn(0, LEVEL_STEPS) / LEVEL_STEPS
        if (halo.scaleX == v && halo.scaleY == v) return
        halo.scaleX = v
        halo.scaleY = v
    }

    /** already committed text is dimmed, the sentence still being recognized is highlighted */
    fun showTranscript(committed: CharSequence, partial: CharSequence) {
        val sb = SpannableStringBuilder()
        // only the tail: a long dictation must not make every update re-layout pages of text
        val tail = if (committed.length > MAX_SHOWN) committed.subSequence(committed.length - MAX_SHOWN, committed.length) else committed
        sb.append(tail)
        sb.setSpan(ForegroundColorSpan(textSecondary), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(partial)
        transcriptText.text = sb
        transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) }
    }

    fun performHaptic() {
        micButton.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** click once on tap; repeat while held (backspace) */
    @SuppressLint("ClickableViewAccessibility")
    private fun setRepeating(view: View, action: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var delay = REPEAT_START_MS
        val repeat = object : Runnable {
            override fun run() {
                // Only while the finger is really still down on a live panel: a panel replaced
                // (rotation, dark mode) or hidden while held may never see its ACTION_UP, and must
                // not go on deleting the user's text by itself.
                if (!view.isPressed || !view.isAttachedToWindow || !view.isShown) return
                action()
                delay = (delay * 0.85).toLong().coerceAtLeast(REPEAT_MIN_MS)
                handler.postDelayed(this, delay)
            }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}

            override fun onViewDetachedFromWindow(v: View) {
                v.isPressed = false
                handler.removeCallbacks(repeat)
            }
        })
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    action()
                    delay = REPEAT_INTERVAL_MS
                    handler.postDelayed(repeat, REPEAT_START_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeat)
                }
            }
            true
        }
        // accessibility services click instead of touching
        view.setOnClickListener { action() }
    }

    companion object {
        private const val LEVEL_STEPS = 6
        private const val HALO_MIN = 0.74f
        private const val MAX_SHOWN = 400
        private const val REPEAT_START_MS = 400L
        private const val REPEAT_INTERVAL_MS = 90L
        private const val REPEAT_MIN_MS = 35L

        val PUNCTUATION = listOf("，", "。", "？", "！", "、", "：")

        /** below this RMS the room is considered silent, so background noise doesn't flicker */
        private const val NOISE_FLOOR = 0.012f

        /** @param rms microphone level, roughly 0..0.3; @return 0..[LEVEL_STEPS] */
        fun levelStep(rms: Float): Int {
            if (rms < NOISE_FLOOR) return 0
            // a bit of compression, so normal speech covers most of the range
            val v = sqrt(rms.coerceAtMost(0.25f) / 0.25f)
            return round(v * LEVEL_STEPS).toInt().coerceIn(1, LEVEL_STEPS)
        }
    }
}
