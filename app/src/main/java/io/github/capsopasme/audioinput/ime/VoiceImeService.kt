/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 capsopasme
 */
package io.github.capsopasme.audioinput.ime

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.view.WindowCompat
import io.github.capsopasme.audioinput.settings.Prefs

/**
 * A voice-only input method: the keyboard is the dictation panel. Meant to sit next to a normal
 * keyboard (e.g. Gboard) and be switched to when dictating; the panel's keyboard button switches
 * back.
 */
class VoiceImeService : InputMethodService() {

    /**
     * Incremented whenever input starts in a different editor (focus moved to another field or
     * app; not on restarts of the same editor). Lets results that arrive later tell whether the
     * editor they were spoken for is still the one being edited.
     */
    var editorGeneration = 0
        private set

    private lateinit var controller: VoiceController

    /** height / punctuation row the current view was built with */
    private var viewConfig: Pair<Int, Boolean>? = null

    override fun onCreate() {
        super.onCreate()
        controller = VoiceController(this)
    }

    override fun onCreateInputView(): View {
        val prefs = Prefs.get(this)
        val config = prefs.panelHeightDp to prefs.showPunctuation
        viewConfig = config
        val panel = VoicePanel(this, config.first, config.second)
        controller.attach(panel)
        return panel.root
    }

    // the panel never needs the fullscreen extract editor, not even in landscape
    override fun onEvaluateFullscreenMode() = false

    // dictation works with a hardware keyboard attached, too
    @SuppressLint("MissingSuperCall")
    override fun onEvaluateInputViewShown() = true

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (!restarting) editorGeneration++
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val prefs = Prefs.get(this)
        if (viewConfig != (prefs.panelHeightDp to prefs.showPunctuation)) {
            // settings changed since the view was built
            setInputView(onCreateInputView())
        }
        controller.onStartInputView(info)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        controller.onFinishInputView()
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        setupNavigationBar()
        controller.onWindowShown()
    }

    override fun onWindowHidden() {
        controller.onWindowHidden()
        super.onWindowHidden()
    }

    override fun onDestroy() {
        controller.destroy()
        super.onDestroy()
    }

    /**
     * Draw the panel's background behind the navigation bar / gesture handle (the panel pads its
     * controls above it), and keep the handle readable on it.
     */
    private fun setupNavigationBar() {
        val w = window?.window ?: return
        // 35+ enforces edge-to-edge anyway
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            WindowCompat.setDecorFitsSystemWindows(w, false)
        }
        @Suppress("DEPRECATION")
        w.navigationBarColor = Color.TRANSPARENT
        w.isNavigationBarContrastEnforced = false
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(w, w.decorView).isAppearanceLightNavigationBars = !night
    }
}
