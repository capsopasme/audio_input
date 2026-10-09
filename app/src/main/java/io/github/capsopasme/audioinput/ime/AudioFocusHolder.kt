/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 capsopasme
 */
package io.github.capsopasme.audioinput.ime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Optional: pause other apps' audio while listening (music from the speaker ends up in the
 * microphone and hurts recognition). Transient focus, so players resume by themselves.
 */
class AudioFocusHolder(ctx: Context) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null

    fun request() {
        if (request != null || am == null) return
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
        if (am.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) request = r
    }

    fun abandon() {
        val r = request ?: return
        request = null
        am?.abandonAudioFocusRequest(r)
    }
}
