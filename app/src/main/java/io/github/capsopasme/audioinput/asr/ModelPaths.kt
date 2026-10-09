/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 capsopasme and Fcitx5 for Android Contributors
 */
package io.github.capsopasme.audioinput.asr

import android.content.Context
import java.io.File

/**
 * Where models live. Shared by both processes, so nothing here keeps state.
 *
 * Models are stored in app-private internal storage (`files/voice-models/<dirName>`):
 * external storage is mounted noexec, and QNN needs real file paths.
 */
object ModelPaths {
    const val VAD_FILE_NAME = "silero_vad.onnx"

    fun rootDir(ctx: Context) = File(ctx.filesDir, "voice-models")

    fun modelDir(ctx: Context, model: SpeechModel) = File(rootDir(ctx), model.dirName)

    fun isInstalled(ctx: Context, model: SpeechModel) =
        model.requiredFiles.all { File(modelDir(ctx, model), it).let { f -> f.isFile && f.length() > 0 } }
}
