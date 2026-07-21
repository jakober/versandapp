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
import java.time.LocalTime

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

        val summary = try {
            app.repository.refreshAllAndDetectChanges()
        } catch (_: Exception) {
            return Result.retry()
        }

        // Nachts (22–6 Uhr) still bleiben.
        if (isQuietHoursNow()) return Result.success()

        if (summary.changed.isNotEmpty()) {
            // Echte Änderungen immer melden (eine Push pro geändertem Paket).
            summary.changed.forEach { notifyStatusChange(it) }
        } else if (app.settings.notifyAlways) {
            // Keine Änderung, aber Nutzer will jede Prüfung bestätigt bekommen.
            notifyChecked(summary.checkedCount)
        }
        return Result.success()
    }

    /** „Nichts Neues"-Zusammenfassung; ersetzt die vorige (fester ID), stapelt nicht. */
    private fun notifyChecked(checkedCount: Int) {
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return

        val openApp = PendingIntent.getActivity(
            applicationContext,
            SUMMARY_NOTIFICATION_ID,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (checkedCount) {
            0 -> "Keine offenen Pakete zu prüfen"
            1 -> "1 Paket geprüft – nichts Neues"
            else -> "$checkedCount Pakete geprüft – nichts Neues"
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Statusprüfung")
            .setContentText(text)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()

        runCatching { manager.notify(SUMMARY_NOTIFICATION_ID, notification) }
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
        private const val SUMMARY_NOTIFICATION_ID = 1_000_002

        /** Nachtruhe 22–6 Uhr: in diesem Fenster keine Hintergrund-Benachrichtigungen. */
        fun isQuietHoursNow(): Boolean {
            val hour = LocalTime.now().hour
            return hour >= 22 || hour < 6
        }

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
