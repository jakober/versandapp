package de.versandapp

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import de.versandapp.data.db.AppDatabase
import de.versandapp.data.log.DiagnosticsLog
import de.versandapp.data.settings.SettingsRepository
import de.versandapp.data.tracking.TrackingRepository
import de.versandapp.worker.MailImportWorker
import de.versandapp.worker.RefreshWorker
import de.versandapp.worker.TestNotificationWorker
import java.util.concurrent.TimeUnit

/**
 * Einfacher manueller DI-Container – für die Projektgröße bewusst ohne
 * Hilt/Koin gehalten.
 */
class VersandApp : Application() {

    lateinit var settings: SettingsRepository
        private set

    lateinit var repository: TrackingRepository
        private set

    override fun onCreate() {
        super.onCreate()

        settings = SettingsRepository(this)
        DiagnosticsLog.init(this)

        // Reine Postfach-App: kein Online-Tracking, keine API-Keys, keine KI-Cloud.
        // Der Status kommt ausschließlich aus den Mails (on-device analysiert).
        repository = TrackingRepository(
            dao = AppDatabase.get(this).parcelDao(),
            providersFactory = { emptyList() },
        )

        RefreshWorker.ensureChannel(this)
        scheduleMailImport()
        // Test-Push-Takt nach Neustart wieder aufnehmen, falls eingeschaltet.
        if (settings.testPushEnabled) TestNotificationWorker.schedule(this, delayMinutes = 2)
    }

    /** Schaltet den 2-Minuten-Test-Push an oder aus (aus den Einstellungen). */
    fun setTestPush(enabled: Boolean) {
        settings.testPushEnabled = enabled
        if (enabled) TestNotificationWorker.schedule(this, delayMinutes = 0)
        else TestNotificationWorker.cancel(this)
    }

    /** Feuert sofort eine einzelne Test-Push (für den „Jetzt testen"-Knopf). */
    fun sendTestNotificationNow() = TestNotificationWorker.showTestNotification(this)

    /**
     * Baut den Postfach-Scanner mit on-device-Analyse (Gemini Nano), sofern das
     * Gerät sie unterstützt – sonst nutzt der Scanner den lokalen Regex-Parser.
     */
    fun buildMailScanner(): de.versandapp.data.mail.ShipmentMailScanner =
        de.versandapp.data.mail.ShipmentMailScanner(
            extractor = de.versandapp.data.mail.createNanoExtractor(this),
        )

    /**
     * Automatischer Mail-Import alle 6 Stunden (sofern Gmail verknüpft ist);
     * neu gefundene Sendungen werden importiert und per Benachrichtigung
     * gemeldet.
     */
    private fun scheduleMailImport() {
        val request = PeriodicWorkRequestBuilder<MailImportWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            MailImportWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
