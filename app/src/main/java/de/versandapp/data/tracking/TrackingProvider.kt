package de.versandapp.data.tracking

import de.versandapp.data.model.Carrier
import de.versandapp.data.model.ParcelStatus

/**
 * Ein einzelnes Statusereignis, wie es von einer Tracking-Quelle geliefert wird
 * (noch ohne Datenbank-IDs).
 */
data class TrackingUpdate(
    val timestamp: Long,
    val description: String,
    val location: String? = null,
    val status: ParcelStatus = ParcelStatus.UNKNOWN,
)

data class TrackingResult(
    val status: ParcelStatus,
    val events: List<TrackingUpdate>,
)

/**
 * Abstraktion über die Herkunft der Trackingdaten.
 *
 * Implementierungen:
 *  - [DemoTrackingProvider]: generiert plausible Beispieldaten, damit die App
 *    ohne API-Schlüssel sofort benutzbar ist.
 *  - [DhlTrackingProvider]: echte Anbindung an die DHL Unified Tracking API
 *    (API-Key nötig, kostenlos unter developer.dhl.com).
 *  - Weitere Carrier lassen sich über eigene Provider oder einen
 *    Aggregator-Dienst (17track, Ship24, AfterShip …) ergänzen.
 */
interface TrackingProvider {
    fun supports(carrier: Carrier): Boolean

    /** Wirft [TrackingException] bei Netz-/API-Fehlern. */
    suspend fun track(trackingNumber: String, carrier: Carrier): TrackingResult
}

class TrackingException(message: String, cause: Throwable? = null) : Exception(message, cause)
