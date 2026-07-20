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
 *  - [ClaudeTrackingProvider]: prüft alle Dienste einheitlich per
 *    Claude-Web-Suche (Anthropic-Key nötig).
 *  - [DemoTrackingProvider]: generiert plausible Beispieldaten, damit die App
 *    ohne API-Schlüssel sofort benutzbar ist.
 *  - Direkte Carrier-APIs oder Aggregatoren (17track, Ship24 …) lassen sich
 *    bei Bedarf als weitere Provider ergänzen.
 */
interface TrackingProvider {
    fun supports(carrier: Carrier): Boolean

    /**
     * Mindestabstand zwischen automatischen Hintergrund-Aktualisierungen.
     * 0 = keine Drosselung. Kostenpflichtige Provider (Claude) setzen hier
     * ein größeres Intervall; manuelle Aktualisierung ignoriert die Drossel.
     */
    val minRefreshIntervalMs: Long
        get() = 0L

    /**
     * Ob der Provider im Hintergrund gerade abgefragt werden darf. Der
     * Claude-Provider beschränkt sich damit auf ein Abendfenster (1×/Tag);
     * manuelle Aktualisierung ignoriert auch diese Einschränkung.
     */
    fun isBackgroundRefreshAllowedNow(): Boolean = true

    /** Wirft [TrackingException] bei Netz-/API-Fehlern. */
    suspend fun track(trackingNumber: String, carrier: Carrier): TrackingResult
}

class TrackingException(message: String, cause: Throwable? = null) : Exception(message, cause)
