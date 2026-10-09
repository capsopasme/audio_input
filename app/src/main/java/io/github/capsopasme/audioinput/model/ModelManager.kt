/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 capsopasme and Fcitx5 for Android Contributors
 */
package io.github.capsopasme.audioinput.model

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import io.github.capsopasme.audioinput.asr.ModelPaths
import io.github.capsopasme.audioinput.asr.SpeechModel
import io.github.capsopasme.audioinput.model.archive.TarExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads / imports / deletes speech models. Lives in the main process.
 *
 * A download streams the .tar.bz2 straight into the extractor (no archive copy on flash), into a
 * temp directory that is renamed into place only once complete and fsync'ed.
 */
object ModelManager {

    private const val TAG = "ModelManager"

    sealed interface State {
        data object NotInstalled : State
        data class Installed(val bytes: Long) : State
        data class Working(val phase: Phase, val done: Long, val total: Long) : State
        data class Failed(val message: String) : State
    }

    enum class Phase { Download, Import }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<SpeechModel, Job>()

    @Volatile
    private var importJob: Job? = null

    private val _states = MutableStateFlow<Map<SpeechModel, State>>(emptyMap())
    val states: StateFlow<Map<SpeechModel, State>> = _states.asStateFlow()

    private val _importState = MutableStateFlow<State?>(null)
    val importState: StateFlow<State?> = _importState.asStateFlow()

    /**
     * Refuse to start when the model can't fit: filling internal storage up to the last byte
     * hurts the whole system (and flash wear, through f2fs garbage collection), and every byte
     * written before the inevitable failure is wasted.
     */
    private fun checkFreeSpace(dir: File, archiveBytes: Long) {
        if (archiveBytes <= 0) return
        dir.mkdirs()
        // extracted size is roughly the archive size (bz2 barely compresses int8 weights)
        val needed = archiveBytes + archiveBytes / 4 + FREE_SPACE_MARGIN
        val available = StatFs(dir.path).availableBytes
        if (available < needed) {
            throw IOException("Not enough storage: need ${needed / MB} MB, ${available / MB} MB available")
        }
    }

    private const val MB = 1024L * 1024L
    private const val FREE_SPACE_MARGIN = 256 * MB

    private fun dirSize(f: File): Long =
        if (f.isFile) f.length() else f.listFiles()?.sumOf { dirSize(it) } ?: 0L

    private fun diskState(ctx: Context, model: SpeechModel): State =
        if (ModelPaths.isInstalled(ctx, model)) State.Installed(dirSize(ModelPaths.modelDir(ctx, model)))
        else State.NotInstalled

    private fun setState(model: SpeechModel, state: State) {
        _states.update { it + (model to state) }
    }

    /** Re-scan disk, keeping states of running jobs. Does file IO, call off the main thread. */
    fun refresh(ctx: Context) {
        cleanupStaleTemp(ctx)
        SpeechModel.entries.forEach { m ->
            if (jobs[m]?.isActive == true) return@forEach
            val old = _states.value[m]
            val new = diskState(ctx, m)
            // keep a failure message visible until the user retries
            if (old is State.Failed && new is State.NotInstalled) return@forEach
            setState(m, new)
        }
    }

    fun refreshAsync(ctx: Context) {
        val appCtx = ctx.applicationContext
        scope.launch { refresh(appCtx) }
    }

    fun isBusy(model: SpeechModel) = jobs[model]?.isActive == true

    /**
     * The job stays in [jobs] until it has really finished (it may be blocked in a network read
     * for a while), so that a new download of the same model can wait for it instead of both
     * writing to - and deleting - the same temp directory.
     */
    fun cancel(model: SpeechModel) {
        jobs[model]?.cancel()
    }

    /**
     * A download interrupted by process death leaves up to ~1 GB in a temp directory that
     * nothing would ever clean up unless the same model is downloaded again.
     */
    private fun cleanupStaleTemp(ctx: Context) {
        ModelPaths.rootDir(ctx).listFiles()?.forEach { f ->
            val stale = when {
                f.name == ".tmp-import" -> importJob?.isActive != true
                f.name.startsWith(".tmp-") -> {
                    val m = SpeechModel.fromDirName(f.name.removePrefix(".tmp-"))
                    m == null || jobs[m]?.isCompleted != false
                }
                // half-copied VAD model; the recognizer process may be writing it right now
                f.name.endsWith(".tmp") -> System.currentTimeMillis() - f.lastModified() > 60_000L
                // a model directory this version doesn't know any more (an update replaced the
                // model with a newer release): nothing would ever load or delete it
                f.isDirectory -> SpeechModel.fromDirName(f.name) == null
                else -> false
            }
            if (stale) {
                Log.i(TAG, "Removing stale ${f.name}")
                f.deleteRecursively()
            }
        }
    }

    fun delete(ctx: Context, model: SpeechModel) {
        cancel(model)
        val appCtx = ctx.applicationContext
        setState(model, State.NotInstalled)
        scope.launch {
            ModelPaths.modelDir(appCtx, model).deleteRecursively()
            setState(model, diskState(appCtx, model))
        }
    }

    /**
     * @param urlPrefix optional mirror prefix, e.g. "https://ghfast.top/";
     * the final url is `prefix + https://github.com/...`, or `{url}` is replaced
     */
    fun download(ctx: Context, model: SpeechModel, urlPrefix: String) {
        if (isBusy(model)) return
        val appCtx = ctx.applicationContext
        val url = urlPrefix.trim().let { p ->
            if (p.isEmpty()) model.defaultUrl
            else if (p.contains("{url}")) p.replace("{url}", model.defaultUrl)
            else p + model.defaultUrl
        }
        val previous = jobs[model]
        setState(model, State.Working(Phase.Download, 0, model.downloadSizeMb * MB))
        jobs[model] = scope.launch {
            // a cancelled download of the same model may still be winding down
            previous?.join()
            val tmp = File(ModelPaths.rootDir(appCtx), ".tmp-${model.dirName}")
            var conn: HttpURLConnection? = null
            try {
                tmp.deleteRecursively()
                checkFreeSpace(ModelPaths.rootDir(appCtx), model.downloadSizeMb * MB)
                tmp.mkdirs()
                Log.i(TAG, "Downloading $model from $url")
                val c = openConnection(url)
                conn = c
                val total = c.contentLengthLong.takeIf { it > 0 } ?: (model.downloadSizeMb * MB)
                checkFreeSpace(ModelPaths.rootDir(appCtx), total)
                val job = currentCoroutineContext()[Job]
                c.inputStream.use { raw ->
                    val counting = CountingInputStream(raw) { read ->
                        setState(model, State.Working(Phase.Download, read, total))
                    }
                    TarExtractor.extractTarBz2(
                        counting, tmp,
                        filter = { model.shouldExtract(it) },
                        isCancelled = { job?.isActive != true }
                    )
                }
                installFromTmp(appCtx, tmp, model)
                setState(model, diskState(appCtx, model))
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to download $model", e)
                tmp.deleteRecursively()
                if (currentCoroutineContext().isActive) {
                    setState(model, State.Failed(e.localizedMessage ?: e.javaClass.simpleName))
                } else {
                    setState(model, diskState(appCtx, model))
                }
            } finally {
                conn?.disconnect()
                currentCoroutineContext()[Job]?.let { jobs.remove(model, it) }
            }
        }
    }

    /**
     * Import a `.tar.bz2` archive downloaded elsewhere (browser / PC).
     * The model type is detected from the top level directory name.
     */
    fun importArchive(ctx: Context, uri: Uri, onFinished: (Result<SpeechModel>) -> Unit) {
        if (importJob?.isActive == true) return
        val appCtx = ctx.applicationContext
        importJob = scope.launch {
            val tmp = File(ModelPaths.rootDir(appCtx), ".tmp-import")
            val result = runCatching {
                tmp.deleteRecursively()
                tmp.mkdirs()
                val total = try {
                    appCtx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                } catch (_: Exception) {
                    null
                }?.takeIf { it > 0 } ?: -1L
                checkFreeSpace(ModelPaths.rootDir(appCtx), total)
                _importState.value = State.Working(Phase.Import, 0, total)
                val job = currentCoroutineContext()[Job]
                val input = appCtx.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open $uri")
                input.use { raw ->
                    val counting = CountingInputStream(raw) { read ->
                        _importState.value = State.Working(Phase.Import, read, total)
                    }
                    TarExtractor.extractTarBz2(
                        counting, tmp,
                        filter = { path ->
                            val top = path.substringBefore('/')
                            SpeechModel.fromDirName(top)?.shouldExtract(path) == true
                        },
                        isCancelled = { job?.isActive != true }
                    )
                }
                val model = tmp.listFiles()?.firstNotNullOfOrNull { SpeechModel.fromDirName(it.name) }
                    ?: throw IOException("Unknown model archive")
                cancel(model)
                jobs[model]?.join()
                installFromTmp(appCtx, tmp, model)
                setState(model, diskState(appCtx, model))
                model
            }
            tmp.deleteRecursively()
            _importState.value = null
            result.exceptionOrNull()?.let { Log.w(TAG, "Failed to import speech model", it) }
            onFinished(result)
        }
    }

    private fun installFromTmp(ctx: Context, tmp: File, model: SpeechModel) {
        val extracted = File(tmp, model.dirName)
        val missing = model.requiredFiles.filterNot { File(extracted, it).isFile }
        if (missing.isNotEmpty()) throw IOException("Archive is missing: ${missing.joinToString()}")
        val dest = ModelPaths.modelDir(ctx, model)
        dest.deleteRecursively()
        dest.parentFile?.mkdirs()
        if (!extracted.renameTo(dest)) throw IOException("Failed to move the model into place")
        tmp.deleteRecursively()
    }

    private fun openConnection(url: String): HttpURLConnection {
        var current = URL(url)
        // follow redirects manually, HttpURLConnection refuses cross-protocol / some cross-host ones
        repeat(8) {
            val conn = current.openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "audio-input")
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                    ?: throw IOException("HTTP $code without Location")
                conn.disconnect()
                current = URL(current, location)
                return@repeat
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                throw IOException("HTTP $code: $current")
            }
            return conn
        }
        throw IOException("Too many redirects")
    }

    private class CountingInputStream(
        input: InputStream,
        private val onRead: (Long) -> Unit
    ) : FilterInputStream(input) {
        private var count = 0L
        private var lastReport = 0L

        private fun advance(n: Long) {
            if (n <= 0) return
            count += n
            if (count - lastReport >= 512 * 1024) {
                lastReport = count
                onRead(count)
            }
        }

        override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { advance(it.toLong()) }

        override fun skip(n: Long): Long = super.skip(n).also { advance(it) }
    }
}
