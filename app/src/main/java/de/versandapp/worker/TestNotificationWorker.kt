package de.versandapp.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.versandapp.R
import de.versandapp.VersandApp
import de.versandapp.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Reiner Testmodus zum Prüfen, ob Push-Benachrichtigungen ankommen: Solange
 * [de.versandapp.data.settings.SettingsRepository.testPushEnabled] aktiv ist,
 * schickt dieser Worker etwa alle 2 Minuten eine Test-Push und plant sich
 * danach selbst neu (WorkManager erlaubt bei echten periodischen Jobs nur
 * mindestens 15 Minuten – für einen kurzen Takt kettet sich der Worker daher
 * über OneTime-Jobs selbst).
 *
 * So lässt sich getrennt prüfen, ob (a) die Benachrichtigungs-Pipeline
 * überhaupt funktioniert und (b) Hintergrundarbeit trotz Akku-Sparmodus läuft.
 */
class TestNotificationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? VersandApp ?: return Result.success()
        // Ausgeschaltet? Dann Kette beenden (keine Neuplanung).
        if (!app.settings.testPushEnabled) return Result.success()

        showTestNotification(applicationContext)
        // Nächsten Lauf in ~2 Minuten einplanen (selbst-ketten).
        schedule(applicationContext, delayMinutes = 2)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "test_push"
        private const val NOTIFICATION_ID_BASE = 2_000_000

        /** Startet bzw. plant den nächsten Test-Push. */
        fun schedule(context: Context, delayMinutes: Long) {
            val request = OneTimeWorkRequestBuilder<TestNotificationWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        /** Stoppt den Test-Push-Takt. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /** Schickt sofort eine einzelne Test-Push (für den „Jetzt testen"-Knopf). */
        fun showTestNotification(context: Context) {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return

            val openApp = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID_BASE,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val id = NOTIFICATION_ID_BASE + (System.currentTimeMillis() % 100_000).toInt()

            val notification = NotificationCompat.Builder(context, RefreshWorker.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_logo)
                .setLargeIcon(appLogoBitmap(context))
                .setContentTitle("Test-Benachrichtigung")
                .setContentText("Push funktioniert – $time")
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build()

            runCatching { manager.notify(id, notification) }
        }
    }
}
