package de.versandapp

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import de.versandapp.data.db.AppDatabase
import de.versandapp.data.tracking.DemoTrackingProvider
import de.versandapp.data.tracking.DhlTrackingProvider
import de.versandapp.data.tracking.TrackingProvider
import de.versandapp.data.tracking.TrackingRepository
import de.versandapp.worker.RefreshWorker
import java.util.concurrent.TimeUnit

/**
 * Einfacher manueller DI-Container – für die Projektgröße bewusst ohne
 * Hilt/Koin gehalten.
 */
class VersandApp : Application() {

    lateinit var repository: TrackingRepository
        private set

    override fun onCreate() {
        super.onCreate()

        val providers = buildList<TrackingProvider> {
            // Echte Carrier-APIs hier registrieren, sobald Keys vorhanden sind:
            if (DHL_API_KEY.isNotBlank()) add(DhlTrackingProvider(DHL_API_KEY))
            // Fallback, damit die App ohne Keys sofort funktioniert:
            add(DemoTrackingProvider())
        }

        repository = TrackingRepository(
            dao = AppDatabase.get(this).parcelDao(),
            providers = providers,
        )

        RefreshWorker.ensureChannel(this)
        scheduleBackgroundRefresh()
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

    companion object {
        /**
         * Kostenlosen Key unter https://developer.dhl.com anlegen und hier
         * eintragen (für Produktion besser über BuildConfig/local.properties
         * injizieren statt im Code).
         */
        const val DHL_API_KEY = ""
    }
}
