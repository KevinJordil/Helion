package ch.kevinjordil.helion.store

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        MinuteSample::class,
        PointSample::class,
        SyncState::class,
        SleepStageSegment::class,
        Activity::class,
        Slot::class,
        Publication::class,
        HealthConnectExportState::class,
    ],
    version = 12,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class HelionDatabase : RoomDatabase() {
    abstract fun minuteSamples(): MinuteSampleDao
    abstract fun pointSamples(): PointSampleDao
    abstract fun syncState(): SyncStateDao
    abstract fun sleepStageSegments(): SleepStageSegmentDao
    abstract fun activities(): ActivityDao
    abstract fun slots(): SlotDao
    abstract fun publications(): PublicationDao
    abstract fun healthConnectExportState(): HealthConnectExportStateDao
}
