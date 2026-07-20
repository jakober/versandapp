package de.versandapp

import android.app.Application
import de.versandapp.data.db.AppDatabase
import de.versandapp.data.tracking.DemoTrackingProvider
import de.versandapp.data.tracking.DhlTrackingProvider
import de.versandapp.data.tracking.TrackingProvider
import de.versandapp.data.tracking.TrackingRepository

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
