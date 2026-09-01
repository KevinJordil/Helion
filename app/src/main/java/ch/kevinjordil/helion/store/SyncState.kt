package ch.kevinjordil.helion.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Single-row table recording the outcome of the last ingestion pass.
 *
 * Deliberately *not* a correctness mechanism. It used to carry a global
 * `lastIngestedTimestamp` watermark, which could only ever track the freshest series and
 * silently excluded every slower one (see [ch.kevinjordil.helion.source.Watermarks]);
 * watermarks are now derived per series from the archive itself, so the column was dropped
 * rather than kept as a tempting second source of truth. What is left is a report: when the
 * last pass ran, and why it failed if it did. [lastError] is null after a successful pass.
 *
 * [triggerFailureStreak] and [lastTriggerAttempt] back the trigger backoff in
 * [ch.kevinjordil.helion.source.Ingestor]: a phone whose Gadgetbridge cannot be triggered
 * (only exposes the per-device Bluetooth Intent API, not the general one) would otherwise
 * pay a 30 s wake-lock every periodic pass forever. Kept in this row rather than a separate
 * store so the bookkeeping rides along with the write every pass already makes, and survives
 * the process being killed between periodic runs -- an in-memory counter would not.
 *
 * [lastFullDetectionRun] is set only by [ch.kevinjordil.helion.activity.ArchiveReanalyzer],
 * never by [ch.kevinjordil.helion.source.Ingestor]'s own per-pass detection call: it answers
 * one question -- "does the whole archive reflect the detection thresholds currently in
 * force, or only whatever a normal ingest pass's day-deep lookback has touched since the
 * thresholds last changed" -- which an ordinary pass's narrow window can never answer. Null
 * until the owner runs a full re-analysis for the first time.
 */
@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey val id: Int = 1,
    val lastSyncAttempt: Long,
    val lastError: String?,
    val triggerFailureStreak: Int = 0,
    val lastTriggerAttempt: Long = 0,
    val lastFullDetectionRun: Long? = null,
)

@Dao
interface SyncStateDao {

    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun get(): SyncState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: SyncState)
}
