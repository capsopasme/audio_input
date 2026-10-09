/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 capsopasme and Fcitx5 for Android Contributors
 */
package io.github.capsopasme.audioinput.asr

import android.os.Build
import io.github.capsopasme.audioinput.R

/**
 * Offline speech recognition models (sherpa-onnx release assets).
 */
enum class SpeechModel(
    val titleRes: Int,
    val descRes: Int,
    /** Release tag + archive name under https://github.com/k2-fsa/sherpa-onnx/releases/download/ */
    val releasePath: String,
    /** Top-level directory inside the archive */
    val dirName: String,
    /** Files (relative to [dirName]) that must exist for the model to be usable */
    val requiredFiles: List<String>,
    /** Rough download size, for the UI and the free space check */
    val downloadSizeMb: Int,
    /** Runs on the Qualcomm HTP (NPU) through QNN */
    val isQnn: Boolean,
    /**
     * The longest audio segment the model takes.
     * QNN models have a fixed input length (anything longer is silently truncated).
     */
    val maxSegmentSeconds: Float,
    /** Whether it's cheap enough to re-run on the growing sentence to show partial results */
    val fastEnoughForPartial: Boolean,
    /** Test wav (relative to [dirName]) kept after extraction, used by the self test */
    val testWav: String,
    /** supports the language / punctuation options (SenseVoice does, Qwen3-ASR decides itself) */
    val hasLanguageOptions: Boolean,
    /**
     * Keep the model loaded in the cached recognizer process after the panel closes.
     * Off for large models, whose resident memory would push many other apps out of the cache.
     */
    val keepLoadedWhenIdle: Boolean = true,
    /** seconds to wait for the model to load before assuming the recognizer hangs */
    val loadTimeoutSeconds: Int = 40,
) {

    /**
     * SenseVoice-Small on the Snapdragon 8 Gen 3 (SM8650, Hexagon v75) NPU.
     * Precompiled QNN context binary (QNN SDK 2.40), fixed 20 s input.
     */
    SenseVoiceQnnSM8650(
        R.string.model_sense_voice_qnn,
        R.string.model_sense_voice_qnn_desc,
        "asr-models-qnn-binary/sherpa-onnx-qnn-SM8650-binary-20-seconds-sense-voice-zh-en-ja-ko-yue-2024-07-17-int8.tar.bz2",
        "sherpa-onnx-qnn-SM8650-binary-20-seconds-sense-voice-zh-en-ja-ko-yue-2024-07-17-int8",
        listOf("model.bin", "tokens.txt"),
        downloadSizeMb = 157,
        isQnn = true,
        maxSegmentSeconds = 19f,
        fastEnoughForPartial = true,
        testWav = "test_wavs/zh.wav",
        hasLanguageOptions = true,
    ),

    /** SenseVoice-Small int8 on the CPU, works on any arm64 phone */
    SenseVoiceCpu(
        R.string.model_sense_voice_cpu,
        R.string.model_sense_voice_cpu_desc,
        "asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
        "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
        listOf("model.int8.onnx", "tokens.txt"),
        downloadSizeMb = 156,
        isQnn = false,
        maxSegmentSeconds = 25f,
        fastEnoughForPartial = true,
        testWav = "test_wavs/zh.wav",
        hasLanguageOptions = true,
    ),

    /** Qwen3-ASR 0.6B int8 on the CPU: most accurate, but slower and large */
    Qwen3Asr(
        R.string.model_qwen3_asr,
        R.string.model_qwen3_asr_desc,
        "asr-models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2",
        "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
        listOf(
            "conv_frontend.onnx",
            "encoder.int8.onnx",
            "decoder.int8.onnx",
            "tokenizer/vocab.json",
            "tokenizer/merges.txt",
            "tokenizer/tokenizer_config.json"
        ),
        downloadSizeMb = 838,
        isQnn = false,
        maxSegmentSeconds = 25f,
        fastEnoughForPartial = false,
        testWav = "test_wavs/codeswitch.wav",
        hasLanguageOptions = false,
        keepLoadedWhenIdle = false,
        loadTimeoutSeconds = 90,
    );

    val defaultUrl: String
        get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/$releasePath"

    /** keep the model files and a single test wav, skip the rest (other wavs, scripts, docs) */
    fun shouldExtract(pathInArchive: String): Boolean {
        val rel = pathInArchive.removePrefix("$dirName/")
        if (rel == pathInArchive) return false // not inside the model directory
        if (rel.startsWith("test_wavs/")) return rel == testWav
        return rel.endsWith(".onnx") || rel.endsWith(".bin") || rel.endsWith(".txt") ||
                rel.endsWith(".json") || rel == "LICENSE" || rel == "README.md"
    }

    companion object {
        fun fromDirName(name: String) = entries.firstOrNull { it.dirName == name }

        fun fromName(name: String?) = entries.firstOrNull { it.name == name }

        /** "" when unknown (Android 11 and older don't report it) */
        val socModel: String
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL.takeUnless { it == Build.UNKNOWN } ?: ""
            } else ""

        /** Snapdragon 8 Gen 3, the only SoC the bundled NPU model was compiled for */
        val isSm8650: Boolean
            get() = socModel.uppercase().startsWith("SM8650")

        val recommended: SpeechModel
            get() = if (isSm8650) SenseVoiceQnnSM8650 else SenseVoiceCpu
    }
}

enum class SpeechLanguage(val titleRes: Int, val senseVoiceCode: String) {
    Auto(R.string.lang_auto, "auto"),
    Chinese(R.string.lang_zh, "zh"),
    English(R.string.lang_en, "en"),
    Cantonese(R.string.lang_yue, "yue"),
    Japanese(R.string.lang_ja, "ja"),
    Korean(R.string.lang_ko, "ko");

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: Auto
    }
}
