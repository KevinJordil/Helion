package ch.kevinjordil.helion.store

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [MinuteSample::class, PointSample::class, SyncState::class, SleepStageSegment::class],
    version = 4,
    exportSchema = true,
)
abstract class HelionDatabase : RoomDatabase() {
    abstract fun minuteSamples(): MinuteSampleDao
    abstract fun pointSamples(): PointSampleDao
    abstract fun syncState(): SyncStateDao
    abstract fun sleepStageSegments(): SleepStageSegmentDao
}
