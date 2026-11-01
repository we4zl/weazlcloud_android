package io.prendie.weazlcloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** WorkManager restores this job after process death/reboot; no Activity is needed. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as CloudApplication
        val repo = app.repo
        val transfers = app.transfers
        try {
            if (repo.demo != null || transfers.stopped) return@withContext Result.success()
            if (!repo.restoreIfNeeded()) return@withContext Result.success()
            if (!transfers.hasScheduledWork()) return@withContext Result.success()
            if (!transfers.permitted(false)) return@withContext Result.success()
            // Session restoration recovers the credential/queue, not upload capabilities.
            // This also validates account/device identity before any background writes.
            repo.refreshAuth()
            if (!repo.capabilities.optBoolean("vault_unlocked")) {
                transfers.condition = "Open WeazlCloud to unlock the server vault"
                return@withContext Result.success()
            }
            setForeground(getForegroundInfo())
            // Yield periodically to Android's scheduler, retaining every durable checkpoint.
            val finished = withTimeoutOrNull(8 * 60 * 1000L) {
                transfers.runScheduledBackup()
                true
            } ?: false
            if (finished) Result.success() else Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            transfers.condition = when (e) {
                is ApiFailure -> e.message ?: "Background backup needs attention"
                else -> "Background backup interrupted; saved transfers will retry"
            }
            Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val context = applicationContext
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("transfers", "Backup and transfers", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, "transfers")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("WeazlCloud")
            .setContentText("Backing up and verifying originals in the background")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        return ForegroundInfo(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
