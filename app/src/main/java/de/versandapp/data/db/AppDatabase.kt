package de.versandapp.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import de.versandapp.data.model.Carrier
import de.versandapp.data.model.Parcel
import de.versandapp.data.model.ParcelStatus
import de.versandapp.data.model.TrackingEvent

class Converters {
    @TypeConverter
    fun fromCarrier(value: Carrier): String = value.name

    @TypeConverter
    fun toCarrier(value: String): Carrier =
        Carrier.entries.firstOrNull { it.name == value } ?: Carrier.OTHER

    @TypeConverter
    fun fromStatus(value: ParcelStatus): String = value.name

    @TypeConverter
    fun toStatus(value: String): ParcelStatus =
        ParcelStatus.entries.firstOrNull { it.name == value } ?: ParcelStatus.UNKNOWN
}

@Database(
    entities = [Parcel::class, TrackingEvent::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun parcelDao(): ParcelDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** v1 → v2: Spalte für das voraussichtliche Zustelldatum ergänzen (verlustfrei). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE parcels ADD COLUMN estimatedDelivery INTEGER")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "versandapp.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
