package org.futo.inputmethod.latin.uix.actions.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.futo.inputmethod.latin.R

/**
 * Runs the model download as a foreground job, so the system keeps it alive after the keyboard
 * closes. The foreground service type (specialUse) matches the one declared for WorkManager's
 * SystemForegroundService in the manifest, which the model training job already uses.
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        startForeground(0)

        var lastPercent = -1
        val ok = LlmModelDownloader.run(applicationContext) { downloaded, total ->
            val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
            if (percent != lastPercent) {
                lastPercent = percent
                // Updating the notification can fail if the service was stopped; ignore that.
                startForeground(percent)
            }
        }
        return if (ok) Result.success() else Result.failure()
    }

    /** Shows or updates the notification. Failing to do so (for example, notifications blocked) must not stop the download. */
    private suspend fun startForeground(percent: Int) {
        try {
            setForeground(foregroundInfo(percent))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Ignored on purpose.
        }
    }

    private fun foregroundInfo(percent: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    applicationContext.getString(R.string.ai_write_download_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.ai_write_download_notification_title))
            .setContentText("$percent%")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_delete, applicationContext.getString(R.string.ai_write_cancel), cancelIntent)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val CHANNEL_ID = "ai_writing_model_download"
        const val NOTIFICATION_ID = 7421
    }
}
