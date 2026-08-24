package ch.kevinjordil.helion.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * A value from an irregular series (stress, SpO2, PAI, temperature, HRV...).
 * [series] is a stable identifier defined in MetricCatalog.
 */
@Entity(tableName = "point_sample", primaryKeys = ["series", "timestamp"])
data class PointSample(
    val series: String,
    val timestamp: Long,
    val value: Double,
)

@Dao
interface PointSampleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(samples: List<PointSample>)

    @Query("SELECT * FROM point_sample WHERE series = :series ORDER BY timestamp DESC LIMIT 1")
    suspend fun latest(series: String): PointSample?

    @Query("SELECT * FROM point_sample WHERE series = :series AND timestamp BETWEEN :from AND :to ORDER BY timestamp")
    suspend fun between(series: String, from: Long, to: Long): List<PointSample>
}
