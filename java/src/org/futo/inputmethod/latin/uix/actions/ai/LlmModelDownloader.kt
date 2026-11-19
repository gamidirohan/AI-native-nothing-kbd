package org.futo.inputmethod.latin.uix.actions.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val TAG = "LlmModelDownloader"

sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : ModelDownloadState
    data object Verifying : ModelDownloadState
    data object Done : ModelDownloadState
    data class Failed(val message: String) : ModelDownloadState
}

/**
 * Downloads the rewrite model from Hugging Face.
 *
 * - Resumes a partial `.part` file with an HTTP Range request.
 * - Checks free space first, and the SHA-256 after, before the file gets its final name.
 * - State lives in this process-wide object, so closing and reopening the AI Writing panel
 *   shows the current progress. The download stops if the keyboard process is killed and
 *   resumes from the partial file on the next attempt.
 */
object LlmModelDownloader {
    private val _state = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val state: StateFlow<ModelDownloadState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Synchronized
    fun start(context: Context, spec: LlmModelSpec = LlmModelStore.DEFAULT_MODEL) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        _state.value = ModelDownloadState.Downloading(0, spec.sizeBytes)

        job = scope.launch {
            try {
                download(appContext, spec)
                _state.value = ModelDownloadState.Done
            } catch (e: CancellationException) {
                _state.value = ModelDownloadState.Idle
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download failed", e)
                _state.value = ModelDownloadState.Failed(e.message ?: "Download failed")
            }
        }
    }

    /** Stops the download but keeps the partial file so it can be resumed. */
    @Synchronized
    fun cancel() {
        job?.cancel()
        job = null
    }

    /** Clears a Done or Failed state back to Idle. */
    fun reset() {
        if (job?.isActive != true) _state.value = ModelDownloadState.Idle
    }

    private suspend fun download(context: Context, spec: LlmModelSpec) {
        val finalFile = LlmModelStore.defaultModelFile(context)
        val partFile = LlmModelStore.partialFile(context)

        if (partFile.length() > spec.sizeBytes) partFile.delete()
        var existing = partFile.length()

        val needed = spec.sizeBytes - existing
        val free = LlmModelStore.directory(context).usableSpace
        if (free < needed + SPACE_MARGIN_BYTES) {
            throw IOException("Not enough free storage. About ${(needed + SPACE_MARGIN_BYTES) / MIB} MB needed.")
        }

        if (existing < spec.sizeBytes) {
            val request = Request.Builder().url(spec.url).apply {
                if (existing > 0) header("Range", "bytes=$existing-")
            }.build()

            client.newCall(request).execute().use { response ->
                when {
                    response.code == 206 -> Unit
                    response.code == 200 -> {
                        // Server ignored the Range header: start over.
                        existing = 0
                        partFile.delete()
                    }
                    else -> throw IOException("Server returned HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Empty response")

                RandomAccessFile(partFile, "rw").use { out ->
                    out.seek(existing)
                    var downloaded = existing
                    var lastReport = 0L
                    val buffer = ByteArray(64 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            if (!currentCoroutineContext().isActive) throw CancellationException()
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                                lastReport = now
                                _state.value = ModelDownloadState.Downloading(downloaded, spec.sizeBytes)
                            }
                        }
                    }
                }
            }
        }

        if (partFile.length() != spec.sizeBytes) {
            // Connection ended early. Keep the partial file so Retry resumes.
            throw IOException("Download was interrupted. Tap Retry to continue.")
        }

        _state.value = ModelDownloadState.Verifying
        val actual = sha256(partFile)
        if (!actual.equals(spec.sha256, ignoreCase = true)) {
            partFile.delete()
            throw IOException("The downloaded file is corrupt. Tap Retry to download it again.")
        }

        finalFile.delete()
        if (!partFile.renameTo(finalFile)) throw IOException("Could not save the model file.")
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                if (!currentCoroutineContext().isActive) throw CancellationException()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val MIB = 1024L * 1024L
    private const val SPACE_MARGIN_BYTES = 200L * MIB
    private const val PROGRESS_INTERVAL_MS = 250L
}
