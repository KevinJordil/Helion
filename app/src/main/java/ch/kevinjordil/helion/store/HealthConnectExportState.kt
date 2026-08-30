package ch.kevinjordil.helion.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Single-row table recording how far [ch.kevinjordil.helion.healthconnect.HealthConnectExporter]
 * has already got, and what its last pass actually did -- the same "not a correctness
 * mechanism, a report" shape [SyncState] already uses for ingestion, and for the same
 * reason: everything here can be recomputed from the archive and Health Connect itself, but
 * keeping a report survives the process being killed between passes.
 *
 * Every `*Watermark` field is Unix seconds, "everything with a timestamp greater than this
 * has already been considered" -- exactly [ch.kevinjordil.helion.source.Watermarks]'s own
 * convention, one column per series because (as that class' own kdoc explains for
 * ingestion) a single shared watermark would silently track only the freshest series. Zero
 * means "nothing exported yet".
 *
 * [heartRateWatermark] backs both the heart-rate and the steps daily-bucket export (see
 * [ch.kevinjordil.helion.healthconnect.HealthConnectExporter]): both are built from the
 * same [MinuteSample] rows, on the same per-UTC-day cadence, so one shared cursor over that
 * table is enough -- there is no separate `stepsWatermark`.
 *
 * [sleepSessionWatermark] is compared against [SleepStageSegment.sessionEnd], the same
 * identity [SleepStageSegmentDao.since] already keys on.
 *
 * [lastRunAttempt] is null until the first pass ever runs. [lastError] is a machine-readable
 * reason (mirroring [ch.kevinjordil.helion.customserver.CustomServerFailureReason]'s own
 * shape, mapped to French only in the UI) and is null after a successful pass.
 *
 * The `*Written` counts describe only the *last* pass, not a running total -- what Réglages
 * shows next to "what it wrote". A pass that wrote nothing new (nothing changed since the
 * last one) still overwrites these with zeros: a stale "wrote 12 last time" from days ago
 * would misrepresent what actually happened just now.
 */
@Entity(tableName = "health_connect_export_state")
data class HealthConnectExportState(
    @PrimaryKey val id: Int = 1,
    val heartRateWatermark: Long = 0,
    val hrvWatermark: Long = 0,
    val spo2Watermark: Long = 0,
    val temperatureWatermark: Long = 0,
    val respiratoryRateWatermark: Long = 0,
    val sleepSessionWatermark: Long = 0,
    val lastRunAttempt: Long? = null,
    val lastError: String? = null,
    val sleepSessionsWritten: Int = 0,
    val exerciseSessionsWritten: Int = 0,
    val heartRateRecordsWritten: Int = 0,
    val stepsRecordsWritten: Int = 0,
    val hrvRecordsWritten: Int = 0,
    val spo2RecordsWritten: Int = 0,
    val temperatureRecordsWritten: Int = 0,
    val respiratoryRateRecordsWritten: Int = 0,
)

@Dao
interface HealthConnectExportStateDao {

    @Query("SELECT * FROM health_connect_export_state WHERE id = 1")
    suspend fun get(): HealthConnectExportState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: HealthConnectExportState)
}
