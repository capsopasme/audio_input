/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 capsopasme and Fcitx5 for Android Contributors
 */
package io.github.capsopasme.audioinput.asr

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.QnnConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import io.github.capsopasme.audioinput.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Wraps a sherpa-onnx [OfflineRecognizer]. Only used inside the `:voice` process, because native
 * failures in QNN / onnxruntime call exit() and would take the input method down otherwise.
 */
class VoiceEngine private constructor(
    /** what was asked for */
    val key: Key,
    /** what is actually loaded: [SpeechModel.SenseVoiceCpu] when the NPU model fell back to it */
    val model: SpeechModel,
    private val recognizer: OfflineRecognizer,
    val loadMillis: Long,
    /** shown in the panel's status line */
    val backendName: String,
) {

    data class Key(
        val model: SpeechModel,
        val language: SpeechLanguage,
        val itn: Boolean,
    )

    /** a CPU engine standing in for an NPU model that can't be used right now */
    val isFallback: Boolean
        get() = model != key.model

    /** @return recognized text, trimmed */
    fun recognize(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, VoiceProtocol.SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun release() = recognizer.release()

    class EngineException(message: String) : Exception(message)

    companion object {
        private const val TAG = "VoiceEngine"

        @Volatile
        private var adspPathSet = false

        /** the big cores; more threads only add contention with the little ones */
        private val cpuThreads: Int
            get() = if (Runtime.getRuntime().availableProcessors() >= 8) 4 else 2

        /**
         * @param forceQnn try the NPU even if its last initialization crashed the process
         * @param avoidQnn use the CPU model for an NPU model (it crashed while recognizing)
         */
        fun create(ctx: Context, key: Key, forceQnn: Boolean = false, avoidQnn: Boolean = false): VoiceEngine {
            if (!key.model.isQnn) return load(ctx, key, key.model, ctx.getString(R.string.backend_cpu))
            val guard = QnnCrashGuard(ctx)
            if (forceQnn) guard.clear()
            val crashed = !forceQnn && guard.crashedBefore()
            if (crashed || (avoidQnn && !forceQnn)) {
                // QNN reports fatal errors with exit(): loading it again would kill the process
                // again, every time the panel opens. Use the CPU model instead if it's there.
                Log.w(TAG, "Not using the NPU (crashed during init: $crashed, avoid: $avoidQnn)")
                val cpu = SpeechModel.SenseVoiceCpu
                if (ModelPaths.isInstalled(ctx, cpu)) {
                    return load(ctx, key, cpu, ctx.getString(R.string.backend_cpu_fallback))
                }
                throw EngineException(ctx.getString(R.string.error_qnn_crashed))
            }
            guard.arm()
            val failure: Exception = try {
                val engine = load(ctx, key, key.model, ctx.getString(R.string.backend_npu))
                // The first inference sets up the graph on the DSP: it is the other place where
                // QNN fails fatally, and doing it now keeps that latency out of the first sentence.
                try {
                    engine.recognize(FloatArray(VoiceProtocol.SAMPLE_RATE / 2))
                } catch (e: Throwable) {
                    engine.release()
                    throw e
                }
                return engine
            } catch (e: Exception) {
                e
            } finally {
                // only reached if the process survived; an exception (missing files, wrong SoC)
                // is reported normally and doesn't disable the NPU. Disarmed before falling back,
                // so that trouble with the CPU model is never blamed on the NPU.
                guard.disarm()
            }
            // A failure QNN reports properly (wrong SoC, no DSP access, runtime mismatch):
            // the CPU model still gives the user a working keyboard. An explicit self test
            // reports the NPU's own error instead.
            val cpu = SpeechModel.SenseVoiceCpu
            if (forceQnn || !ModelPaths.isInstalled(ctx, cpu)) throw failure
            Log.w(TAG, "NPU model failed to load, using the CPU model", failure)
            return load(ctx, key, cpu, ctx.getString(R.string.backend_cpu_fallback))
        }

        private fun load(ctx: Context, key: Key, model: SpeechModel, backendName: String): VoiceEngine {
            val dir = ModelPaths.modelDir(ctx, model)
            val missing = model.requiredFiles.filterNot { File(dir, it).isFile }
            if (missing.isNotEmpty()) {
                throw EngineException(ctx.getString(R.string.error_model_missing, missing.joinToString()))
            }
            val language = if (model.hasLanguageOptions) key.language.senseVoiceCode else "auto"
            val modelConfig = when (model) {
                SpeechModel.SenseVoiceQnnSM8650, SpeechModel.SenseVoiceQnnSM8550 -> {
                    checkQnnUsable(ctx, model.qnn!!)
                    OfflineModelConfig(
                        provider = "qnn",
                        senseVoice = OfflineSenseVoiceModelConfig(
                            language = language,
                            useInverseTextNormalization = key.itn,
                            qnnConfig = QnnConfig(
                                backendLib = "libQnnHtp.so",
                                systemLib = "libQnnSystem.so",
                                contextBinary = File(dir, "model.bin").absolutePath,
                            ),
                        ),
                        tokens = File(dir, "tokens.txt").absolutePath,
                        // only used for feature extraction
                        numThreads = 2,
                    )
                }
                SpeechModel.SenseVoiceCpu -> OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = File(dir, "model.int8.onnx").absolutePath,
                        language = language,
                        useInverseTextNormalization = key.itn,
                    ),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    numThreads = cpuThreads,
                )
                SpeechModel.Qwen3Asr -> OfflineModelConfig(
                    qwen3Asr = OfflineQwen3AsrModelConfig(
                        convFrontend = File(dir, "conv_frontend.onnx").absolutePath,
                        encoder = File(dir, "encoder.int8.onnx").absolutePath,
                        decoder = File(dir, "decoder.int8.onnx").absolutePath,
                        tokenizer = File(dir, "tokenizer").absolutePath,
                    ),
                    tokens = "",
                    numThreads = cpuThreads,
                )
            }
            val config = OfflineRecognizerConfig(modelConfig = modelConfig)
            val start = SystemClock.elapsedRealtime()
            val recognizer = try {
                OfflineRecognizer(assetManager = null, config = config)
            } catch (e: IllegalArgumentException) {
                throw EngineException(ctx.getString(R.string.error_init_failed, e.message ?: ""))
            }
            val elapsed = SystemClock.elapsedRealtime() - start
            Log.i(TAG, "Loaded $model in ${elapsed}ms")
            return VoiceEngine(key, model, recognizer, elapsed, backendName)
        }

        private fun checkQnnUsable(ctx: Context, target: QnnTarget) {
            val soc = SpeechModel.socModel
            Log.i(TAG, "SoC model: '$soc', model built for ${target.socPrefix} (HTP v${target.htpArch})")
            if (soc.isNotBlank() && !target.matches(soc)) {
                throw EngineException(ctx.getString(R.string.error_qnn_soc, target.socPrefix, soc))
            }
            val libDir = ctx.applicationInfo.nativeLibraryDir
            val required = listOf("libQnnHtp.so", "libQnnSystem.so", target.stubLib, target.skelLib)
            val missing = required.filterNot { File(libDir, it).exists() }
            if (missing.isNotEmpty()) {
                throw EngineException(ctx.getString(R.string.error_qnn_libs, missing.joinToString()))
            }
            if (!adspPathSet) {
                // without ADSP_LIBRARY_PATH the DSP can't find libQnnHtpV<arch>Skel.so (error 1008)
                OfflineRecognizer.prependAdspLibraryPath(libDir)
                adspPathSet = true
            }
        }

        /**
         * Silero VAD bundled as a raw resource, copied to internal storage.
         * Written to a temp file, fsync'ed, then renamed: a power loss can never leave a
         * truncated model behind (onnxruntime would abort() on it and the recognizer process
         * would crash on every start). The copy survives app updates, so it's replaced when
         * the bundled one differs.
         */
        fun createVad(ctx: Context, model: SpeechModel, minSilenceMs: Int): Vad {
            val file = File(ModelPaths.rootDir(ctx), ModelPaths.VAD_FILE_NAME)
            // ~640 KB, read only when the VAD is (re)created
            val bundled = ctx.resources.openRawResource(R.raw.silero_vad).use { it.readBytes() }
            if (!file.isFile || !sameContent(file, bundled)) {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                FileOutputStream(tmp).use { out ->
                    out.write(bundled)
                    out.fd.sync()
                }
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    throw EngineException("Failed to install the VAD model")
                }
            }
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = file.absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = minSilenceMs / 1000f,
                    minSpeechDuration = 0.25f,
                    windowSize = VAD_WINDOW,
                    // soft limit: past it VAD cuts at the next short pause. The service force-cuts
                    // at maxSegmentSeconds, this leaves room to find a natural break first.
                    maxSpeechDuration = (model.maxSegmentSeconds - 4f).coerceAtLeast(5f),
                ),
                sampleRate = VoiceProtocol.SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
            )
            return Vad(assetManager = null, config = config)
        }

        private fun sameContent(file: File, bytes: ByteArray): Boolean =
            file.length() == bytes.size.toLong() && file.readBytes().contentEquals(bytes)

        const val VAD_WINDOW = 512
    }
}

/**
 * Remembers that loading the NPU model killed the `:voice` process. QNN reports fatal errors
 * (no access to the DSP, a context binary that doesn't match the runtime, ...) with exit(), so
 * there is no exception to catch: a marker file is written before loading and deleted after it,
 * and if it is still there next time, the previous attempt never returned.
 *
 * The marker holds the APK's update time (an app update, possibly with other QNN libraries, tries
 * the NPU again), the boot count, and the pid and time of the load. A load cut short by something
 * that isn't the NPU's fault - the phone rebooting, the system reclaiming memory, the user force
 * stopping the app - must not disable the NPU until the next self test: the boot count and the
 * system's record of how that process ended (Android 11+) tell those cases apart. When there is no
 * such record the load counts as a crash, as before. One tiny write per model load, which is rare:
 * the loaded model is kept in the cached process.
 */
internal class QnnCrashGuard(private val ctx: Context) {
    private val file = File(ctx.noBackupFilesDir, "voice-qnn-loading")

    private val stamp: String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
    } catch (_: Exception) {
        "0"
    }

    private class Marker(val stamp: String, val bootCount: Int, val pid: Int, val time: Long)

    private fun readMarker(): Marker? {
        if (!file.exists()) return null
        val parts = try {
            file.readText()
        } catch (_: IOException) {
            ""
        }.trim().split(' ')
        return Marker(
            stamp = parts[0],
            bootCount = parts.getOrNull(1)?.toIntOrNull() ?: -1,
            pid = parts.getOrNull(2)?.toIntOrNull() ?: 0,
            time = parts.getOrNull(3)?.toLongOrNull() ?: 0L,
        )
    }

    /** why the interrupted load in [m] is not the NPU's fault; null if it may well be */
    private fun innocentReason(m: Marker): String? {
        if (m.stamp != stamp) return "left behind by another version of the app"
        val boot = bootCount(ctx)
        if (m.bootCount >= 0 && boot >= 0 && m.bootCount != boot) return "interrupted by a reboot"
        val reason = exitReason(m) ?: return null
        return if (reason in BENIGN_EXIT_REASONS) "the process was ended by the system (reason $reason)" else null
    }

    /** how the process that wrote [m] ended, as recorded by the system; null if unknown */
    private fun exitReason(m: Marker): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || m.pid <= 0) return null
        return try {
            val am = ctx.getSystemService(ActivityManager::class.java) ?: return null
            am.getHistoricalProcessExitReasons(ctx.packageName, m.pid, 4).firstOrNull {
                it.processName == voiceProcessName(ctx) && it.timestamp >= m.time - CLOCK_SLACK_MS
            }?.reason
        } catch (_: Exception) {
            null
        }
    }

    /** the process that wrote [m] is alive: a load is in progress right now, not a crash */
    private fun isLoadingNow(m: Marker): Boolean {
        if (m.pid <= 0) return false
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return false
        return try {
            am.runningAppProcesses?.any { it.pid == m.pid && it.processName == voiceProcessName(ctx) } == true
        } catch (_: Exception) {
            false
        }
    }

    /** Called by the recognizer process before loading the NPU model. */
    fun crashedBefore(): Boolean {
        val m = readMarker() ?: return false
        val why = innocentReason(m) ?: return true
        Log.i(TAG, "Ignoring an interrupted NPU load: $why")
        file.delete()
        return false
    }

    fun arm() {
        try {
            file.writeText("$stamp ${bootCount(ctx)} ${Process.myPid()} ${System.currentTimeMillis()}")
        } catch (e: IOException) {
            Log.w(TAG, "Cannot write marker", e)
        }
    }

    fun disarm() {
        file.delete()
    }

    fun clear() = disarm()

    companion object {
        private const val TAG = "QnnCrashGuard"

        /** wall clock adjustments between writing the marker and the process dying */
        private const val CLOCK_SLACK_MS = 60_000L

        /** ways a process ends that have nothing to do with what it was doing */
        private val BENIGN_EXIT_REASONS = setOf(
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE,
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_FREEZER,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED,
        )

        private fun voiceProcessName(ctx: Context) = ctx.packageName + ":voice"

        private fun bootCount(ctx: Context): Int = try {
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)
        } catch (_: Exception) {
            -1
        }

        /**
         * For the settings screen: the NPU is currently skipped because loading it crashed.
         * Read only, it never touches the marker (the recognizer process may be loading).
         */
        fun isTripped(ctx: Context): Boolean {
            val guard = QnnCrashGuard(ctx)
            val m = guard.readMarker() ?: return false
            return !guard.isLoadingNow(m) && guard.innocentReason(m) == null
        }
    }
}
