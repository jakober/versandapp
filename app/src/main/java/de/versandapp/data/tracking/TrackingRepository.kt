package de.versandapp.data.tracking

import de.versandapp.data.db.ParcelDao
import de.versandapp.data.model.Carrier
import de.versandapp.data.model.Parcel
import de.versandapp.data.model.ParcelWithEvents
import de.versandapp.data.model.TrackingEvent
import kotlinx.coroutines.flow.Flow

/**
 * Zentrale Fachlogik: verwaltet Pakete in der lokalen Datenbank und holt
 * Statusupdates über den jeweils passenden [TrackingProvider].
 *
 * [providers] wird der Reihe nach durchsucht; der erste Provider, der den
 * Carrier unterstützt, gewinnt. Der Demo-Provider steht als Fallback am Ende.
 */
class TrackingRepository(
    private val dao: ParcelDao,
    private val providers: List<TrackingProvider>,
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
        runCatching { refresh(id) }
        return id
    }

    suspend fun deleteParcel(parcel: Parcel) = dao.delete(parcel)

    /** Holt den aktuellen Stand für ein Paket und ersetzt dessen Verlauf. */
    suspend fun refresh(parcelId: Long) {
        val parcel = dao.getAll().firstOrNull { it.id == parcelId } ?: return
        val provider = providers.firstOrNull { it.supports(parcel.carrier) } ?: return

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

    /** Aktualisiert alle Pakete; Fehler einzelner Pakete brechen den Rest nicht ab. */
    suspend fun refreshAll() {
        dao.getAll().forEach { parcel ->
            runCatching { refresh(parcel.id) }
        }
    }

    /** Bereits verfolgte Trackingnummern – für die Duplikat-Erkennung beim Mail-Import. */
    suspend fun trackedNumbers(): Set<String> =
        dao.getAll().map { it.trackingNumber }.toSet()

    /**
     * Aktualisiert alle Pakete und liefert diejenigen zurück, deren Status
     * sich geändert hat (für Benachrichtigungen aus dem Hintergrund-Worker).
     */
    suspend fun refreshAllAndDetectChanges(): List<Parcel> {
        val statusBefore = dao.getAll().associate { it.id to it.status }
        refreshAll()
        return dao.getAll().filter { parcel ->
            val before = statusBefore[parcel.id]
            before != null && before != parcel.status
        }
    }
}
