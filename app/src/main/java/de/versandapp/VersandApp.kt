package de.versandapp

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import de.versandapp.data.ai.ClaudeTranslator
import de.versandapp.data.db.AppDatabase
import de.versandapp.data.settings.SettingsRepository
import de.versandapp.data.tracking.ClaudeTrackingProvider
import de.versandapp.data.tracking.DemoTrackingProvider
import de.versandapp.data.tracking.DhlTrackingProvider
import de.versandapp.data.tracking.OpenAiTrackingProvider
import de.versandapp.data.tracking.Ship24Provider
import de.versandapp.data.tracking.TrackingProvider
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

        val translator = ClaudeTranslator()
        repository = TrackingRepository(
            dao = AppDatabase.get(this).parcelDao(),
            providersFactory = ::buildProviders,
            translate = { texts ->
                val key = settings.anthropicApiKey
                if (key.isBlank()) texts else translator.toGerman(key, texts)
            },
        )

        RefreshWorker.ensureChannel(this)
        scheduleBackgroundRefresh()
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
     * Provider-Kette, bei jeder Aktualisierung neu aufgebaut (Keys aus den
     * Einstellungen wirken sofort). Reihenfolge = Priorität; die Repository-
     * Logik geht sie der Reihe nach durch und nimmt die erste Quelle, die
     * verlässliche Daten liefert:
     * 1. DHL-API (kostenlos, zuverlässigste Quelle) – nur für DHL/Post
     * 2. Claude-Online-Suche für alle Dienste (inkl. China) – kostenlos/günstig
     * 3. OpenAI-/ChatGPT-Online-Suche als zusätzliche Quelle (wenn Key gesetzt)
     * 4. Ship24-API als kostenpflichtiger Notnagel – nur wenn 1–3 nichts fanden
     * 5. Demo-Daten, nur wenn gar kein Key hinterlegt ist (App läuft sofort)
     */
    private fun buildProviders(): List<TrackingProvider> = buildList {
        val current = settings.current()
        if (current.dhlApiKey.isNotBlank()) add(DhlTrackingProvider(current.dhlApiKey))
        if (current.anthropicApiKey.isNotBlank()) add(ClaudeTrackingProvider(current.anthropicApiKey))
        if (current.openAiApiKey.isNotBlank()) {
            add(OpenAiTrackingProvider(current.openAiApiKey, current.openAiTrackingModel))
        }
        if (current.ship24ApiKey.isNotBlank()) add(Ship24Provider(current.ship24ApiKey))
        if (isEmpty()) add(DemoTrackingProvider())
    }

    /** Stündliches Polling im Hintergrund; benachrichtigt bei Statuswechsel. */
    private fun scheduleBackgroundRefresh() {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            RefreshWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

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
