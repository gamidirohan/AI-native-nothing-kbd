package org.futo.inputmethod.latin.uix.actions.ai

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.futo.inputmethod.latin.uix.getSettingBlocking
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val TAG = "LlmModelDownloader"

sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState

    /** Wi-Fi only is on and the current connection is metered. */
    data object WaitingForUnmetered : ModelDownloadState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : ModelDownloadState
    data object Verifying : ModelDownloadState
    data object Done : ModelDownloadState
    data class Failed(val message: String) : ModelDownloadState
}

/**
 * Downloads the rewrite model from Hugging Face.
 *
 * - The work runs in a WorkManager foreground job ([ModelDownloadWorker]), so it carries on if
 *   the keyboard is closed and is restarted if Android kills the process.
 * - Resumes a partial `.part` file with an HTTP Range request.
 * - Checks free space first, and the SHA-256 after, before the file gets its final name.
 * - [state] is shared process-wide, so reopening the AI Writing panel shows current progress.
 */
object LlmModelDownloader {
    const val WORK_NAME = "ai_writing_model_download"

    private val _state = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val state: StateFlow<ModelDownloadState> = _state.asStateFlow()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** True if the active connection is metered (mobile data, or Wi-Fi marked as metered). */
    fun isMeteredConnection(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.isActiveNetworkMetered
    }

    /**
     * Starts the download unless one is already running.
     *
     * @param allowMetered download even if Wi-Fi only is on and the connection is metered
     */
    fun start(
        context: Context,
        allowMetered: Boolean = false,
        spec: LlmModelSpec = LlmModelStore.DEFAULT_MODEL
    ) {
        val appContext = context.applicationContext

        val wifiOnly = appContext.getSettingBlocking(AI_DOWNLOAD_WIFI_ONLY)
        if (wifiOnly && !allowMetered && isMeteredConnection(appContext)) {
            _state.value = ModelDownloadState.WaitingForUnmetered
            return
        }

        _state.value = ModelDownloadState.Downloading(LlmModelStore.partialFile(appContext).length(), spec.sizeBytes)

        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()

        // KEEP: if a download is already running, tapping again does nothing.
        WorkManager.getInstance(appContext).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** Stops the download but keeps the partial file so it can be resumed. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        _state.value = ModelDownloadState.Idle
    }

    /** Clears a Done, Failed or WaitingForUnmetered state back to Idle. */
    fun reset() {
        if (_state.value !is ModelDownloadState.Downloading && _state.value !is ModelDownloadState.Verifying) {
            _state.value = ModelDownloadState.Idle
        }
    }

    /**
     * Runs the whole download. Called by [ModelDownloadWorker]; suspends until finished.
     * Returns true on success, false on failure (the reason is in [state]).
     */
    suspend fun run(
        context: Context,
        spec: LlmModelSpec = LlmModelStore.DEFAULT_MODEL,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): Boolean {
        val appContext = context.applicationContext
        return try {
            download(appContext, spec, onProgress)
            _state.value = ModelDownloadState.Done
            true
        } catch (e: CancellationException) {
            _state.value = ModelDownloadState.Idle
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Download failed", e)
            _state.value = ModelDownloadState.Failed(e.message ?: "Download failed")
            false
        }
    }

    private suspend fun download(
        context: Context,
        spec: LlmModelSpec,
        onProgress: suspend (Long, Long) -> Unit
    ) {
        val finalFile = LlmModelStore.defaultModelFile(context)
        val partFile = LlmModelStore.partialFile(context)

        if (partFile.length() > spec.sizeBytes) partFile.delete()
        var existing = partFile.length()
        _state.value = ModelDownloadState.Downloading(existing, spec.sizeBytes)

        val needed = spec.sizeBytes - existing
        val free = LlmModelStore.directory(context).usableSpace
        if (free < needed + LlmModelStore.SPACE_MARGIN_BYTES) {
            throw IOException("Not enough free storage. About ${(needed + LlmModelStore.SPACE_MARGIN_BYTES) / MIB} MB needed.")
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
                                onProgress(downloaded, spec.sizeBytes)
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

        RewriteEngineProvider.release()
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
    private const val PROGRESS_INTERVAL_MS = 250L
}
