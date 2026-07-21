package de.versandapp.data.model

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

enum class ParcelStatus(val displayName: String) {
    UNKNOWN("Unbekannt"),
    REGISTERED("Angekündigt"),
    IN_TRANSIT("Unterwegs"),
    OUT_FOR_DELIVERY("In Zustellung"),
    DELIVERED("Zugestellt"),
    FAILED("Problem"),
}

@Entity(tableName = "parcels")
data class Parcel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackingNumber: String,
    val carrier: Carrier,
    /** Optionaler, vom Nutzer vergebener Name, z. B. "Neue Kopfhörer". */
    val label: String? = null,
    val status: ParcelStatus = ParcelStatus.UNKNOWN,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUpdated: Long? = null,
    /** Voraussichtliches Zustelldatum (Unix-ms), falls die Quelle es liefert. */
    val estimatedDelivery: Long? = null,
)

@Entity(
    tableName = "tracking_events",
    foreignKeys = [
        ForeignKey(
            entity = Parcel::class,
            parentColumns = ["id"],
            childColumns = ["parcelId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("parcelId")],
)
data class TrackingEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val parcelId: Long,
    val timestamp: Long,
    val description: String,
    val location: String? = null,
    val status: ParcelStatus = ParcelStatus.UNKNOWN,
)

data class ParcelWithEvents(
    @Embedded val parcel: Parcel,
    @Relation(parentColumn = "id", entityColumn = "parcelId")
    val events: List<TrackingEvent>,
) {
    val latestEvent: TrackingEvent?
        get() = events.maxByOrNull { it.timestamp }
}
