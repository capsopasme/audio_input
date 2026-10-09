/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 capsopasme and Fcitx5 for Android Contributors
 */
package io.github.capsopasme.audioinput.asr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import kotlin.math.sqrt

/**
 * Captures 16 kHz mono microphone audio on a background thread.
 *
 * Recording happens in the input method process on purpose: while the keyboard is visible the
 * system binds it like a foreground app, which is what grants microphone access. The `:voice`
 * process never has that.
 *
 * Every [start] gets its own worker with its own running flag: a quick stop() + start() can never
 * leave two threads feeding the same session, and the old thread's cleanup can never stop the new
 * one. Callbacks run on the capture thread and carry the token passed to [start], so the receiver
 * can tell which recording a chunk belongs to.
 */
class AudioCapture(private val listener: Listener) {

    interface Listener {
        /** @param pcm 16 bit samples, @param rms level of the chunk in [0, 1] */
        fun onAudio(pcm: ShortArray, rms: Float, token: Int)

        fun onCaptureFailed(e: Throwable, token: Int)

        /**
         * Only digital silence arrives: the microphone is muted by the system privacy toggle, or
         * another app (a phone call, a recorder) has it and Android silences everyone else.
         * Called again with false once real audio comes back.
         */
        fun onSilentInput(silent: Boolean, token: Int)
    }

    private class Worker {
        @Volatile
        var running = true

        /** set by [requestStop], invoked on the capture thread after the last [Listener.onAudio] */
        private var onStopped: (() -> Unit)? = null
        private var finished = false

        /**
         * Hand-over between [stop] and the capture thread ending, under one lock: either the
         * thread is still going and will invoke [callback], or it has already finished and the
         * caller is told so. Without it a thread ending on its own (a failed read) could miss a
         * callback set at the same moment, and whoever waits for it would wait forever.
         *
         * @return false if the capture thread has already finished; [callback] is not invoked then
         */
        @Synchronized
        fun requestStop(callback: (() -> Unit)?): Boolean {
            running = false
            if (finished) return false
            onStopped = callback
            return true
        }

        /** capture thread, once at the very end; @return the callback to invoke, if any */
        @Synchronized
        fun finish(): (() -> Unit)? {
            finished = true
            running = false
            return onStopped
        }
    }

    @Volatile
    private var current: Worker? = null

    val isRunning get() = current?.running == true

    /** @return false if the microphone permission is missing; other failures are reported async */
    fun start(context: Context, token: Int): Boolean {
        if (isRunning) return true
        if (!hasPermission(context)) return false
        val worker = Worker()
        current = worker
        Thread({ run(worker, token) }, "voice-capture").start()
        return true
    }

    @SuppressLint("MissingPermission")
    private fun open(): AudioRecord {
        val rate = VoiceProtocol.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) throw IllegalStateException("AudioRecord.getMinBufferSize=$minBuf")
        // ~0.5 s of headroom, so a busy moment on the device doesn't drop audio
        val bufferBytes = maxOf(minBuf * 2, rate)
        var lastError: Exception? = null
        // VOICE_RECOGNITION: no echo cancellation / AGC tuned for calls, which is what ASR wants.
        // Plain MIC as a fallback for audio HALs that don't offer it.
        for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val record = try {
                AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            } catch (e: Exception) {
                lastError = e
                continue
            }
            if (record.state == AudioRecord.STATE_INITIALIZED) return record
            record.release()
            lastError = IllegalStateException("AudioRecord not initialized (source $source)")
        }
        throw lastError ?: IllegalStateException("AudioRecord not initialized")
    }

    private fun startRecording(record: AudioRecord) {
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("AudioRecord failed to start (microphone busy?)")
        }
    }

    private fun release(record: AudioRecord?) {
        record ?: return
        try {
            record.stop()
        } catch (_: IllegalStateException) {
        }
        record.release()
    }

    private fun run(worker: Worker, token: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val chunk = ShortArray(VoiceProtocol.SAMPLE_RATE / 10) // 100 ms
        var record: AudioRecord? = null
        var reopened = 0
        var emptyReads = 0
        var zeroRun = 0
        var reportedSilent = false
        try {
            record = open()
            startRecording(record)
            while (worker.running) {
                val n = record!!.read(chunk, 0, chunk.size)
                if (n == AudioRecord.ERROR_DEAD_OBJECT && reopened < MAX_REOPEN && worker.running) {
                    // the audio server restarted or the input route changed (a headset was
                    // plugged in): the old record is dead, a new one works
                    Log.w(TAG, "AudioRecord died, reopening")
                    reopened++
                    release(record)
                    record = null
                    record = open()
                    startRecording(record)
                    continue
                }
                if (n < 0) throw IllegalStateException("AudioRecord.read=$n")
                if (n == 0) {
                    // a blocking read should never return 0; don't spin the CPU if it does
                    if (++emptyReads > 20) throw IllegalStateException("AudioRecord returns no data")
                    Thread.sleep(10)
                    continue
                }
                emptyReads = 0
                val pcm = chunk.copyOf(n)
                var sum = 0.0
                var allZero = true
                for (s in pcm) {
                    if (s.toInt() != 0) allZero = false
                    val v = s / 32768.0
                    sum += v * v
                }
                if (allZero) {
                    zeroRun += n
                    if (!reportedSilent && zeroRun >= SILENT_SAMPLES) {
                        reportedSilent = true
                        listener.onSilentInput(true, token)
                    }
                } else {
                    zeroRun = 0
                    if (reportedSilent) {
                        reportedSilent = false
                        listener.onSilentInput(false, token)
                    }
                }
                // also deliver the chunk read right after stop(): it is the tail of the speech
                listener.onAudio(pcm, sqrt(sum / n).toFloat(), token)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "capture failed", e)
            if (worker.running) listener.onCaptureFailed(e, token)
        } finally {
            worker.running = false
            release(record)
            if (current === worker) current = null
        }
        // non-null only if stop() was called; runs even if the last read failed
        worker.finish()?.invoke()
    }

    /**
     * Asynchronous: the capture thread finishes its current read (<= 100ms), delivers it,
     * releases the microphone and then calls [onStopped] on the capture thread.
     *
     * @return false if nothing was being captured (or the capture thread had just ended by
     * itself), [onStopped] will not be called then
     */
    fun stop(onStopped: (() -> Unit)? = null): Boolean {
        val w = current ?: return false
        current = null
        return w.requestStop(onStopped)
    }

    companion object {
        private const val TAG = "AudioCapture"
        private const val MAX_REOPEN = 3

        /** 1.5 s of exact zeros never happens with a working microphone (there is always noise) */
        private const val SILENT_SAMPLES = VoiceProtocol.SAMPLE_RATE * 3 / 2

        fun hasPermission(context: Context) =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
}
