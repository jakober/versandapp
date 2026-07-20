package de.versandapp.data.tracking

import de.versandapp.data.db.ParcelDao
import de.versandapp.data.model.Carrier
import de.versandapp.data.model.Parcel
import de.versandapp.data.model.ParcelStatus
import de.versandapp.data.model.ParcelWithEvents
import de.versandapp.data.model.TrackingEvent
import kotlinx.coroutines.flow.Flow

/**
 * Zentrale Fachlogik: verwaltet Pakete in der lokalen Datenbank und holt
 * Statusupdates über den jeweils passenden [TrackingProvider].
 *
 * [providersFactory] wird bei jeder Aktualisierung ausgewertet, damit in den
 * Einstellungen geänderte API-Keys sofort wirken. Der erste Provider der
 * Liste, der den Carrier unterstützt, gewinnt.
 */
class TrackingRepository(
    private val dao: ParcelDao,
    private val providersFactory: () -> List<TrackingProvider>,
) {

    fun observeParcels(): Flow<List<ParcelWithEvents>> = dao.observeAll()

    fun observeParcel(id: Long): Flow<ParcelWithEvents?> = dao.observeById(id)

    /** Legt ein Paket an und lädt direkt den ersten Status. Gibt die neue ID zurück. */
    suspend fun addParcel(trackingNumber: String, carrier: Carrier, label: String?): Long {
        val existing = dao.findByTrackingNumber(trackingNumber)
        if (existing != null) return existing.id

        val id = dao.insert(
            Parcel(
                trackingNumber = trackingNumber,
                carrier = carrier,
                label = label?.takeIf { it.isNotBlank() },
            )
        )
        runCatching { refresh(id, force = true) }
        return id
    }

    suspend fun deleteParcel(parcel: Parcel) = dao.delete(parcel)

    /**
     * Holt den aktuellen Stand für ein Paket und ersetzt dessen Verlauf.
     * Bei [force] = false respektiert die Abfrage die Drosselung und das
     * Zeitfenster des Providers sowie den Zustellstatus (zugestellte Pakete
     * werden im Hintergrund nicht mehr abgefragt).
     */
    suspend fun refresh(parcelId: Long, force: Boolean = false) {
        val parcel = dao.getAll().firstOrNull { it.id == parcelId } ?: return
        val provider = providersFactory().firstOrNull { it.supports(parcel.carrier) } ?: return

        if (!force) {
            if (parcel.status == ParcelStatus.DELIVERED) return
            if (!provider.isBackgroundRefreshAllowedNow()) return
            val lastUpdated = parcel.lastUpdated
            if (lastUpdated != null &&
                System.currentTimeMillis() - lastUpdated < provider.minRefreshIntervalMs
            ) {
                return
            }
        }

        val result = provider.track(parcel.trackingNumber, parcel.carrier)

        dao.replaceEvents(
            parcelId = parcel.id,
            events = result.events.map {
                TrackingEvent(
                    parcelId = parcel.id,
                    timestamp = it.timestamp,
                    description = it.description,
                    location = it.location,
                    status = it.status,
                )
            },
        )
        dao.update(
            parcel.copy(
                status = result.status,
                lastUpdated = System.currentTimeMillis(),
            )
        )
    }

    /**
     * Aktualisiert alle noch nicht zugestellten Pakete; Fehler einzelner
     * Pakete brechen den Rest nicht ab. Zugestellte Pakete werden auch bei
     * [force] übersprungen (ihr Status ändert sich nicht mehr) – ein
     * einzelnes Paket lässt sich in der Detailansicht trotzdem aktualisieren.
     */
    suspend fun refreshAll(force: Boolean = false) {
        dao.getAll()
            .filter { it.status != ParcelStatus.DELIVERED }
            .forEach { parcel ->
                runCatching { refresh(parcel.id, force) }
            }
    }

    /** Bereits verfolgte Trackingnummern – für die Duplikat-Erkennung beim Mail-Import. */
    suspend fun trackedNumbers(): Set<String> =
        dao.getAll().map { it.trackingNumber }.toSet()

    /**
     * Aktualisiert alle Pakete (mit Drosselung) und liefert diejenigen zurück,
     * deren Status sich geändert hat (für Benachrichtigungen aus dem Worker).
     */
    suspend fun refreshAllAndDetectChanges(): List<Parcel> {
        val statusBefore = dao.getAll().associate { it.id to it.status }
        refreshAll(force = false)
        return dao.getAll().filter { parcel ->
            val before = statusBefore[parcel.id]
            before != null && before != parcel.status
        }
    }
}
