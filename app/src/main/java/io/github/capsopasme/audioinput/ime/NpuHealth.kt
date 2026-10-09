/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 capsopasme
 */
package io.github.capsopasme.audioinput.ime

/**
 * Whether the NPU model has been crashing the recognizer while recognizing. Main thread only.
 *
 * Process-wide on purpose: the input method service is destroyed whenever the user switches back
 * to their usual keyboard and created again for the next dictation, so state kept in it would be
 * forgotten after every switch, and a model that crashes on every sentence would keep getting
 * another try.
 */
internal object NpuHealth {
    /** the recognizer died during a session this many times in a row */
    var crashesInARow = 0

    /** use the CPU model instead of the NPU until [reset] or until this process restarts */
    var avoidQnn = false

    /** the NPU works again (its self test passed) */
    fun reset() {
        crashesInARow = 0
        avoidQnn = false
    }
}
