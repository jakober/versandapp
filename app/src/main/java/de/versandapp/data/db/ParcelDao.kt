package de.versandapp.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import de.versandapp.data.model.Parcel
import de.versandapp.data.model.ParcelWithEvents
import de.versandapp.data.model.TrackingEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface ParcelDao {

    @Transaction
    @Query("SELECT * FROM parcels ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ParcelWithEvents>>

    @Transaction
    @Query("SELECT * FROM parcels WHERE id = :id")
    fun observeById(id: Long): Flow<ParcelWithEvents?>

    @Query("SELECT * FROM parcels")
    suspend fun getAll(): List<Parcel>

    @Query("SELECT * FROM parcels WHERE trackingNumber = :trackingNumber LIMIT 1")
    suspend fun findByTrackingNumber(trackingNumber: String): Parcel?

    @Insert
    suspend fun insert(parcel: Parcel): Long

    @Update
    suspend fun update(parcel: Parcel)

    @Delete
    suspend fun delete(parcel: Parcel)

    @Insert
    suspend fun insertEvents(events: List<TrackingEvent>)

    @Query("DELETE FROM tracking_events WHERE parcelId = :parcelId")
    suspend fun deleteEventsFor(parcelId: Long)

    @Transaction
    suspend fun replaceEvents(parcelId: Long, events: List<TrackingEvent>) {
        deleteEventsFor(parcelId)
        insertEvents(events)
    }
}
