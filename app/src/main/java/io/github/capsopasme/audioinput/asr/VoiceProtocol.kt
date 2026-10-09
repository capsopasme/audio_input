/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 capsopasme and Fcitx5 for Android Contributors
 */
package io.github.capsopasme.audioinput.asr

/**
 * Messenger protocol between the input method process ([VoiceClient]) and the isolated
 * recognizer process ([VoiceRecognitionService], `:voice`).
 *
 * `Message.arg1` always carries the session id, so that stale events can be dropped.
 */
object VoiceProtocol {
    const val SAMPLE_RATE = 16000

    // client -> service
    const val MSG_START = 1
    const val MSG_AUDIO = 2
    const val MSG_STOP = 3
    const val MSG_CANCEL = 4
    const val MSG_SELF_TEST = 5

    /** load the model (and VAD) without starting a session; arg1 is 0, no reply */
    const val MSG_PRELOAD = 6

    // service -> client
    const val EVT_LOADING = 101
    const val EVT_READY = 102
    const val EVT_PARTIAL = 103
    const val EVT_FINAL = 104
    const val EVT_ERROR = 105
    const val EVT_DONE = 106
    const val EVT_TEST_RESULT = 107

    // bundle keys
    const val KEY_MODEL = "model"
    const val KEY_LANGUAGE = "language"
    const val KEY_ITN = "itn"
    const val KEY_PARTIAL = "partial"
    const val KEY_SILENCE_MS = "silence_ms"

    /** MSG_START: stop the session after this much audio without a recognized sentence, 0 = never */
    const val KEY_IDLE_STOP_MS = "idle_stop_ms"

    /** MSG_SELF_TEST: retry the NPU even if its last initialization crashed the process */
    const val KEY_FORCE_QNN = "force_qnn"

    /**
     * MSG_START / MSG_PRELOAD: don't use the NPU in this session, use the CPU model instead.
     * Set by the client after the recognizer process died more than once while recognizing.
     */
    const val KEY_AVOID_QNN = "avoid_qnn"

    /** EVT_DONE: the session ended by itself because nobody spoke for [KEY_IDLE_STOP_MS] */
    const val KEY_AUTO_STOPPED = "auto_stopped"

    /** MSG_AUDIO: 16 bit PCM, mono, [SAMPLE_RATE] (half the size of floats over binder) */
    const val KEY_PCM16 = "pcm16"
    const val KEY_TEXT = "text"
    const val KEY_MESSAGE = "message"
    const val KEY_BACKEND = "backend"
    const val KEY_FALLBACK = "fallback"
    const val KEY_LOAD_MS = "load_ms"
    const val KEY_DECODE_MS = "decode_ms"
    const val KEY_AUDIO_MS = "audio_ms"
}
