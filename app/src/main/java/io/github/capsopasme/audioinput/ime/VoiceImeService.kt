/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 capsopasme
 */
package io.github.capsopasme.audioinput.ime

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
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
        registerSwitchReceiver()
    }

    override fun onWindowHidden() {
        unregisterSwitchReceiver()
        controller.onWindowHidden()
        super.onWindowHidden()
    }

    /**
     * Switch requests from the status bar module, accepted only while the panel is on screen
     * (registered dynamically: nothing is listening otherwise). The result code 1 tells the module
     * that this input method takes care of it; without it the module switches by itself.
     */
    private var switchReceiver: BroadcastReceiver? = null

    private fun registerSwitchReceiver() {
        if (switchReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION_SWITCH_IME || !isInputViewShown) return
                if (isOrderedBroadcast) resultCode = 1
                controller.requestSwitch(intent.getStringExtra(EXTRA_TARGET))
            }
        }
        try {
            ContextCompat.registerReceiver(
                this, r, IntentFilter(ACTION_SWITCH_IME), PERMISSION_SWITCH_IME, null,
                ContextCompat.RECEIVER_EXPORTED
            )
            switchReceiver = r
        } catch (_: Exception) {
        }
    }

    private fun unregisterSwitchReceiver() {
        val r = switchReceiver ?: return
        switchReceiver = null
        try {
            unregisterReceiver(r)
        } catch (_: Exception) {
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // dark mode switched while the panel is on screen: keep the gesture handle readable
        if (isInputViewShown) setupNavigationBar()
    }

    override fun onDestroy() {
        unregisterSwitchReceiver()
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

    companion object {
        const val ACTION_SWITCH_IME = "io.github.capsopasme.audioinput.action.SWITCH_IME"
        const val EXTRA_TARGET = "target"
        const val PERMISSION_SWITCH_IME = "io.github.capsopasme.audioinput.permission.SWITCH_IME"
    }
}
