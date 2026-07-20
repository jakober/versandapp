package de.versandapp.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.versandapp.R
import de.versandapp.VersandApp
import de.versandapp.data.model.Parcel
import de.versandapp.ui.MainActivity

/**
 * Pollt regelmäßig alle Sendungen (Planung siehe [VersandApp.onCreate]) und
 * zeigt eine lokale Benachrichtigung, wenn sich ein Status geändert hat.
 *
 * Das ist der "Push ohne Server": Android erlaubt periodische Hintergrundarbeit
 * frühestens alle 15 Minuten – für Paketstatus völlig ausreichend. Echtes
 * FCM-Push bräuchte ein Backend, das für alle Nutzer pollt.
 */
class RefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? VersandApp ?: return Result.failure()

        val changed = try {
            app.repository.refreshAllAndDetectChanges()
        } catch (_: Exception) {
            return Result.retry()
        }

        changed.forEach { notifyStatusChange(it) }
        return Result.success()
    }

    private fun notifyStatusChange(parcel: Parcel) {
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return

        val openApp = PendingIntent.getActivity(
            applicationContext,
            parcel.id.toInt(),
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(parcel.label ?: parcel.trackingNumber)
            .setContentText("${parcel.carrier.displayName}: ${parcel.status.displayName}")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()

        runCatching { manager.notify(parcel.id.toInt(), notification) }
    }

    companion object {
        const val CHANNEL_ID = "status_updates"
        const val WORK_NAME = "parcel_refresh"

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Statusänderungen",
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
    }
}
