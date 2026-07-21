package de.versandapp.data.tracking

import de.versandapp.data.model.Carrier
import de.versandapp.data.model.ParcelStatus
import kotlin.math.absoluteValue
import kotlinx.coroutines.delay

/**
 * Liefert deterministische Beispieldaten (abgeleitet aus dem Hash der
 * Trackingnummer), damit die App ohne API-Schlüssel demonstrier- und testbar
 * ist. Dieselbe Nummer ergibt immer denselben Verlauf.
 */
class DemoTrackingProvider : TrackingProvider {

    override fun supports(carrier: Carrier): Boolean = true

    override val progressLabel: String = "Demo-Daten werden geladen …"

    override suspend fun track(trackingNumber: String, carrier: Carrier): TrackingResult {
        delay(400) // simulierte Netzwerklatenz

        val seed = trackingNumber.hashCode().absoluteValue
        val stageCount = (seed % 5) + 1 // 1..5 erreichte Stationen
        val now = System.currentTimeMillis()
        val hour = 60L * 60L * 1000L

        val stages = listOf(
            Triple(ParcelStatus.REGISTERED, "Sendung elektronisch angekündigt", null),
            Triple(ParcelStatus.IN_TRANSIT, "Sendung im Startpaketzentrum bearbeitet", "Hamburg"),
            Triple(ParcelStatus.IN_TRANSIT, "Sendung im Zielpaketzentrum eingetroffen", "München"),
            Triple(ParcelStatus.OUT_FOR_DELIVERY, "Sendung in Zustellfahrzeug geladen", "München"),
            Triple(ParcelStatus.DELIVERED, "Sendung zugestellt", "München"),
        )

        val events = stages.take(stageCount).mapIndexed { index, (status, description, location) ->
            TrackingUpdate(
                timestamp = now - (stageCount - index) * (10 + seed % 14) * hour,
                description = description,
                location = location,
                status = status,
            )
        }

        return TrackingResult(
            status = events.last().status,
            events = events,
        )
    }
}
