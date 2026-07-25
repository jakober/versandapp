package de.versandapp.data.tracking

import de.versandapp.data.db.ParcelDao
import de.versandapp.data.log.DiagnosticsLog
import de.versandapp.data.model.Carrier
import de.versandapp.data.model.Parcel
import de.versandapp.data.model.ParcelStatus
import de.versandapp.data.model.ParcelWithEvents
import de.versandapp.data.model.TrackingEvent
import kotlinx.coroutines.flow.Flow

/** Ergebnis einer einzelnen Statusabfrage – Grundlage für die Fortschrittsanzeige. */
sealed interface RefreshOutcome {
    data class Updated(
        val status: de.versandapp.data.model.ParcelStatus,
        val eventCount: Int,
        val source: String = "",
    ) : RefreshOutcome
    data class Skipped(val reason: String) : RefreshOutcome
    data class Failed(val message: String) : RefreshOutcome
}

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
    /** Übersetzt Verlaufstexte ins Deutsche; Fallback = Originaltexte. */
    private val translate: suspend (List<String>) -> List<String> = { it },
) {

    fun observeParcels(): Flow<List<ParcelWithEvents>> = dao.observeAll()

    fun observeParcel(id: Long): Flow<ParcelWithEvents?> = dao.observeById(id)

    /**
     * Legt ein Paket an und lädt direkt den ersten Status. [initialStatus]
     * setzt einen aus der Mail abgeleiteten Status (z. B. Amazon), der
     * erhalten bleibt, wenn kein Online-Provider Daten liefert.
     * Gibt die neue ID zurück.
     */
    suspend fun addParcel(
        trackingNumber: String,
        carrier: Carrier,
        label: String?,
        initialStatus: ParcelStatus? = null,
    ): Long {
        val existing = dao.findByTrackingNumber(trackingNumber)
        if (existing != null) return existing.id

        val id = dao.insert(
            Parcel(
                trackingNumber = trackingNumber,
                carrier = carrier,
                label = label?.takeIf { it.isNotBlank() },
                status = initialStatus ?: ParcelStatus.UNKNOWN,
            )
        )
        refresh(id, force = true)
        return id
    }

    suspend fun deleteParcel(parcel: Parcel) = dao.delete(parcel)

    /**
     * Holt den aktuellen Stand für ein Paket, ersetzt dessen Verlauf und
     * liefert das Ergebnis zurück (für die Fortschrittsanzeige). Wirft nicht.
     * Bei [force] = false respektiert die Abfrage die Drosselung und das
     * Zeitfenster des Providers sowie den Zustellstatus (zugestellte Pakete
     * werden im Hintergrund nicht mehr abgefragt).
     */
    suspend fun refresh(
        parcelId: Long,
        force: Boolean = false,
        onProgress: (String) -> Unit = {},
    ): RefreshOutcome {
        val parcel = dao.getAll().firstOrNull { it.id == parcelId }
            ?: return RefreshOutcome.Skipped("Paket nicht gefunden")
        val supporting = providersFactory().filter { it.supports(parcel.carrier) }
        if (supporting.isEmpty()) return RefreshOutcome.Skipped("Kein Tracking-Dienst verfügbar")

        if (!force && parcel.status == ParcelStatus.DELIVERED) {
            return RefreshOutcome.Skipped("Bereits zugestellt")
        }

        // Provider-Kette der Reihe nach durchgehen: der erste, der verlässliche
        // Daten liefert, gewinnt. Dadurch bleibt eine kostenpflichtige Quelle
        // (Ship24) ein echter Notnagel – sie wird nur angefragt, wenn die freien
        // Quellen davor (DHL-API, Claude-Online-Suche) nichts gefunden haben.
        val parcelName = parcel.label ?: parcel.trackingNumber
        var eligibleCount = 0
        var throttled = false
        var outsideWindow = false
        var lastFailure: String? = null
        for (provider in supporting) {
            if (!force) {
                if (!provider.isBackgroundRefreshAllowedNow()) {
                    outsideWindow = true
                    continue
                }
                val lastUpdated = parcel.lastUpdated
                if (lastUpdated != null &&
                    System.currentTimeMillis() - lastUpdated < provider.minRefreshIntervalMs
                ) {
                    throttled = true
                    continue
                }
            }
            eligibleCount++
            val source = provider.progressLabel.substringBefore(" wird").substringBefore(" recher")
            onProgress(provider.progressLabel)
            val result = try {
                provider.track(parcel.trackingNumber, parcel.carrier)
            } catch (e: Exception) {
                lastFailure = e.message ?: "Unbekannter Fehler"
                // Jede fehlgeschlagene Quelle mit echtem Fehler ins Protokoll schreiben
                // (auch warum z. B. ChatGPT nichts fand) und kurz im Live-Fenster zeigen.
                DiagnosticsLog.add(parcelName, source, lastFailure, ok = false)
                onProgress("$source: ${lastFailure.take(120)}")
                continue
            }
            persistResult(parcel, result)
            DiagnosticsLog.add(
                parcelName,
                source,
                "Treffer · ${result.status.displayName} · ${result.events.size} Ereignisse",
                ok = true,
            )
            return RefreshOutcome.Updated(result.status, result.events.size, source)
        }

        if (eligibleCount == 0) {
            return RefreshOutcome.Skipped(
                when {
                    throttled -> "Kürzlich aktualisiert"
                    outsideWindow -> "Außerhalb des Abfragefensters"
                    else -> "Kein Tracking-Dienst verfügbar"
                }
            )
        }
        return RefreshOutcome.Failed(lastFailure ?: "Kein Status gefunden")
    }

    /** Speichert ein Abfrageergebnis: Verlauf ins Deutsche übersetzen und ablegen. */
    private suspend fun persistResult(parcel: Parcel, result: TrackingResult) {
        // Verlaufstexte vorab ins Deutsche übersetzen (ein kurzer Aufruf für alle)
        val germanDescriptions = runCatching {
            translate(result.events.map { it.description })
        }.getOrDefault(result.events.map { it.description })

        dao.replaceEvents(
            parcelId = parcel.id,
            events = result.events.mapIndexed { index, update ->
                TrackingEvent(
                    parcelId = parcel.id,
                    timestamp = update.timestamp,
                    description = germanDescriptions.getOrElse(index) { update.description },
                    location = update.location,
                    status = update.status,
                )
            },
        )
        dao.update(
            parcel.copy(
                status = result.status,
                lastUpdated = System.currentTimeMillis(),
                // Nur überschreiben, wenn die Quelle ein Datum lieferte, sonst altes behalten.
                estimatedDelivery = result.estimatedDelivery ?: parcel.estimatedDelivery,
            )
        )
    }

    /** Ändert Label, Carrier und Status eines Pakets (Korrektur durch den Nutzer). */
    suspend fun updateParcel(parcel: Parcel, label: String?, carrier: Carrier, status: ParcelStatus) {
        dao.update(
            parcel.copy(
                label = label?.takeIf { it.isNotBlank() },
                carrier = carrier,
                status = status,
                lastUpdated = System.currentTimeMillis(),
            )
        )
    }

    /**
     * Aktualisiert den Status einer bereits verfolgten Sendung anhand einer Mail
     * (z. B. Amazon „in Zustellung"/„zugestellt"). Nur **vorwärts** – ein aus der
     * Mail abgeleiteter Status überschreibt einen weiter fortgeschrittenen nie.
     * Legt bei echter Änderung einen Verlaufseintrag „Laut E-Mail: …" an.
     * Gibt true zurück, wenn tatsächlich aktualisiert wurde.
     */
    suspend fun updateStatusFromMail(trackingNumber: String, mailStatus: ParcelStatus): Boolean {
        val parcel = dao.findByTrackingNumber(trackingNumber) ?: return false
        if (statusRank(mailStatus) <= statusRank(parcel.status)) return false
        dao.insertEvents(
            listOf(
                TrackingEvent(
                    parcelId = parcel.id,
                    timestamp = System.currentTimeMillis(),
                    description = "Laut E-Mail: ${mailStatus.displayName}",
                    location = null,
                    status = mailStatus,
                )
            )
        )
        dao.update(parcel.copy(status = mailStatus, lastUpdated = System.currentTimeMillis()))
        return true
    }

    /** Fortschritt eines Status (nur für „nur vorwärts"-Vergleich). */
    private fun statusRank(status: ParcelStatus): Int = when (status) {
        ParcelStatus.UNKNOWN -> 0
        ParcelStatus.REGISTERED -> 1
        ParcelStatus.IN_TRANSIT -> 2
        ParcelStatus.OUT_FOR_DELIVERY -> 3
        ParcelStatus.DELIVERED -> 4
        ParcelStatus.FAILED -> 2
    }

    /** Alle noch nicht zugestellten Pakete (Reihenfolge wie in der Liste). */
    suspend fun openParcels(): List<Parcel> =
        dao.getAll()
            .filter { it.status != ParcelStatus.DELIVERED }
            .sortedByDescending { it.createdAt }

    /**
     * Aktualisiert alle noch nicht zugestellten Pakete; Fehler einzelner
     * Pakete brechen den Rest nicht ab. Zugestellte Pakete werden auch bei
     * [force] übersprungen (ihr Status ändert sich nicht mehr) – ein
     * einzelnes Paket lässt sich in der Detailansicht trotzdem aktualisieren.
     */
    suspend fun refreshAll(force: Boolean = false) {
        openParcels().forEach { parcel ->
            refresh(parcel.id, force)
        }
    }

    /** Bereits verfolgte Trackingnummern – für die Duplikat-Erkennung beim Mail-Import. */
    suspend fun trackedNumbers(): Set<String> =
        dao.getAll().map { it.trackingNumber }.toSet()

    /** Ergebnis eines Hintergrundlaufs: geprüfte offene Pakete + geänderte. */
    data class RefreshSummary(val checkedCount: Int, val changed: List<Parcel>)

    /**
     * Aktualisiert alle Pakete (mit Drosselung) und liefert eine Zusammenfassung:
     * wie viele offene Pakete geprüft wurden und welche ihren Status geändert
     * haben (für Benachrichtigungen aus dem Worker).
     */
    suspend fun refreshAllAndDetectChanges(): RefreshSummary {
        val checkedCount = openParcels().size
        val statusBefore = dao.getAll().associate { it.id to it.status }
        refreshAll(force = false)
        val changed = dao.getAll().filter { parcel ->
            val before = statusBefore[parcel.id]
            before != null && before != parcel.status
        }
        return RefreshSummary(checkedCount = checkedCount, changed = changed)
    }
}
