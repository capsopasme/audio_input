/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 capsopasme
 */
package io.github.capsopasme.audioinput.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import io.github.capsopasme.audioinput.asr.SpeechLanguage
import io.github.capsopasme.audioinput.asr.SpeechModel

/**
 * Settings, read by the keyboard and written by [SettingsFragment] (same main process).
 * The recognizer process never reads them: everything it needs is sent along with each request,
 * which keeps SharedPreferences out of multi-process trouble.
 *
 * Keys and defaults must match res/xml/settings.xml.
 */
class Prefs private constructor(val sp: SharedPreferences) {

    var model: SpeechModel
        get() = SpeechModel.fromName(sp.getString(KEY_MODEL, null)) ?: SpeechModel.recommended
        set(value) = sp.edit().putString(KEY_MODEL, value.name).apply()

    val language: SpeechLanguage
        get() = SpeechLanguage.fromName(sp.getString(KEY_LANGUAGE, null))

    val itn get() = sp.getBoolean(KEY_ITN, true)
    val partial get() = sp.getBoolean(KEY_PARTIAL, true)
    val autoStart get() = sp.getBoolean(KEY_AUTO_START, true)
    val silenceMs get() = sp.getInt(KEY_SILENCE_MS, 600).coerceIn(200, 2000)
    val idleStopSeconds get() = sp.getInt(KEY_IDLE_STOP_S, 20).coerceIn(5, 120)
    val autoSpace get() = sp.getBoolean(KEY_AUTO_SPACE, true)
    val dropTrailingPeriod get() = sp.getBoolean(KEY_DROP_PERIOD, false)
    val showPunctuation get() = sp.getBoolean(KEY_SHOW_PUNCT, true)
    val haptics get() = sp.getBoolean(KEY_HAPTICS, true)
    val pauseMedia get() = sp.getBoolean(KEY_PAUSE_MEDIA, false)
    val panelHeightDp get() = sp.getInt(KEY_PANEL_HEIGHT, 250).coerceIn(180, 400)

    val downloadPrefix: String
        get() = sp.getString(KEY_DOWNLOAD_PREFIX, "") ?: ""

    companion object {
        const val KEY_MODEL = "model"
        const val KEY_LANGUAGE = "language"
        const val KEY_ITN = "itn"
        const val KEY_PARTIAL = "partial"
        const val KEY_AUTO_START = "auto_start"
        const val KEY_SILENCE_MS = "silence_ms"
        const val KEY_IDLE_STOP_S = "idle_stop_s"
        const val KEY_AUTO_SPACE = "auto_space"
        const val KEY_DROP_PERIOD = "drop_trailing_period"
        const val KEY_SHOW_PUNCT = "show_punctuation"
        const val KEY_HAPTICS = "haptics"
        const val KEY_PAUSE_MEDIA = "pause_media"
        const val KEY_PANEL_HEIGHT = "panel_height_dp"
        const val KEY_DOWNLOAD_PREFIX = "download_prefix"

        @Volatile
        private var instance: Prefs? = null

        fun get(ctx: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext))
                .also { instance = it }
        }
    }
}
