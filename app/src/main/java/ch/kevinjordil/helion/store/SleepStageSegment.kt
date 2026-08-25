package ch.kevinjordil.helion.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * One contiguous stretch of a single sleep stage, as reported by the device's own
 * `HUAMI_SLEEP_SESSION_SAMPLE` hypnogram -- see
 * [ch.kevinjordil.helion.source.parseSleepSessionBlob] for the blob this is decoded from.
 *
 * `point_sample` is not the right shape for this: a stage segment is a span, not an
 * instantaneous value, and several segments belong together as one session. This table
 * carries both.
 *
 * [sessionEnd] is the wake time (Unix seconds) of the session this segment belongs to --
 * the export's own session identifier, since the device does not give sessions a separate
 * id. All segments sharing a [sessionEnd] came from the same blob. A device export can
 * carry more than one row for what is, physically, the same night (a preliminary snapshot
 * superseded by a later, more complete one, or occasionally a stale duplicate) -- each
 * still lands here as its own group of segments keyed by its own [sessionEnd]; picking the
 * one that actually matches a given [ch.kevinjordil.helion.ui.sleep.SleepEpisode] is done
 * where the two are joined, not here.
 *
 * [startTimestamp] and [endTimestamp] are both Unix seconds, both inclusive of the minute
 * they name (i.e. [endTimestamp] is the start of the segment's *last* minute, not one past
 * it -- matching how the blob itself encodes `end`).
 *
 * [stage] is the device's own type code (see `DeviceSleepStage`), not
 * [ch.kevinjordil.helion.ui.sleep.SleepPhase]'s numbering -- this table stores what the
 * device reported, unmapped, the same way [MinuteSample] does for its own raw fields; the
 * UI-facing mapping happens at the display boundary.
 */
@Entity(tableName = "sleep_stage_segment", primaryKeys = ["sessionEnd", "startTimestamp"])
data class SleepStageSegment(
    val sessionEnd: Long,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val stage: Int,
)

@Dao
interface SleepStageSegmentDao {

    /** Idempotent by (session, segment start): replaying an export never duplicates a segment. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(segments: List<SleepStageSegment>)

    /** Every segment of every session whose window overlaps `[from, to]`, for matching against an episode. */
    @Query(
        "SELECT * FROM sleep_stage_segment WHERE sessionEnd IN " +
            "(SELECT DISTINCT sessionEnd FROM sleep_stage_segment WHERE startTimestamp <= :to AND endTimestamp >= :from) " +
            "ORDER BY sessionEnd, startTimestamp",
    )
    suspend fun overlapping(from: Long, to: Long): List<SleepStageSegment>

    /** The watermark for ingestion: the most recent session already stored, by its own wake time. */
    @Query("SELECT MAX(sessionEnd) FROM sleep_stage_segment")
    suspend fun latestSessionEnd(): Long?
}
