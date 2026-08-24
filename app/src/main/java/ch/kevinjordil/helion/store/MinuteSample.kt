package ch.kevinjordil.helion.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One minute of raw device data, exactly as reported by the device.
 * Never edited after ingestion: everything derived lives in its own table.
 * [timestamp] is Unix seconds, rounded down to the minute.
 */
@Entity(tableName = "minute_sample")
data class MinuteSample(
    @PrimaryKey val timestamp: Long,
    val steps: Int?,
    val intensity: Int?,
    val rawKind: Int?,
    val heartRate: Int?,
    val sleepStage: Int?,
)

@Dao
interface MinuteSampleDao {

    /** Idempotent by primary key: replaying an export never duplicates a minute. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(samples: List<MinuteSample>)

    @Query("SELECT * FROM minute_sample WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp")
    suspend fun between(from: Long, to: Long): List<MinuteSample>

    @Query("SELECT MAX(timestamp) FROM minute_sample")
    suspend fun latestTimestamp(): Long?
}
