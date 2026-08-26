package ch.kevinjordil.helion.ui.sleep

import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.store.SleepStageSegment
import ch.kevinjordil.helion.ui.metric.Reading
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How far back nights are read for the history list and the personal baseline behind it. */
private const val LOOKBACK_DAYS = 30L

private const val RESPIRATORY_RATE_SERIES = "respiratory_rate"

/** One minute, in seconds -- matches [segmentSleepEpisodes]'s own cadence unit. */
private const val CADENCE_SECONDS = 60L

/**
 * Reads minute samples for the lookback window, segments them into [SleepEpisode]s (see
 * [segmentSleepEpisodes]), keeps only the ones classified as a night, and attaches each
 * one's respiratory rate readings and device sleep-stage segments -- two joins
 * [segmentSleepEpisodes] itself cannot do, since it only ever sees
 * [ch.kevinjordil.helion.store.MinuteSample]s.
 *
 * [zone] follows the same pattern as [ch.kevinjordil.helion.ui.metric.MetricReader]:
 * defaults to the device's zone, and tests pass a fixed one so date attribution does not
 * depend on the machine running them.
 *
 * A night whose device stage segments can be matched (see [matchSession]) has its
 * boundaries -- and everything derived from them: duration, awakenings, efficiency,
 * minimum heart rate, respiratory rate -- rebuilt around those segments instead of the
 * minute-derived ones from [segmentSleepEpisodes] (see [boundToDeviceSegments]). This
 * device's own minute table keeps flagging a stretch after the real wake-up as asleep, so
 * the minute-derived segmentation over-runs the true wake time, and its brief-awakening
 * tolerance can then stretch that over-run even further; the device's own hypnogram does
 * not share that failure mode and is what Gadgetbridge itself reports against. Minute-derived
 * segmentation stays the fallback for a night with no blob at all, or one whose blob failed
 * [ch.kevinjordil.helion.source.parseSleepSessionBlob]'s validation upstream -- see
 * [boundToDeviceSegments].
 */
class SleepReader(
    private val db: HelionDatabase,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val thresholds: SleepThresholds = SleepThresholds(),
) {

    /** Nights only, most recent last, over the last [LOOKBACK_DAYS] days. */
    suspend fun loadNights(now: Long): List<SleepEpisode> = withContext(dispatcher) {
        val from = now - LOOKBACK_DAYS * 86_400
        val minutes = db.minuteSamples().between(from, now)
        val episodes = segmentSleepEpisodes(minutes, zone, now, thresholds)
        val nights = episodes.filter { it.kind == SleepEpisodeKind.NIGHT }
        if (nights.isEmpty()) return@withContext emptyList()

        val sessions = db.sleepStageSegments().overlapping(from, now)
            .groupBy { it.sessionEnd }
            .values
            .mapNotNull { Session.of(it) }

        nights.sortedBy { it.wokeAt }.map { episode ->
            val session = matchSession(episode, sessions)
            val boundedEpisode = session?.let { boundToDeviceSegments(episode, it, minutes) } ?: episode
            val stageSegments = session?.let { clipToEpisode(it, boundedEpisode) } ?: emptyList()
            val respiratoryRate = db.pointSamples()
                .between(RESPIRATORY_RATE_SERIES, boundedEpisode.fellAsleepAt, boundedEpisode.wokeAt)
                .map { Reading(it.timestamp, it.value) }
                .sortedBy { it.timestamp }
            // A night with real device stage segments gets its awakening count and duration
            // from them instead of the minute-derived figures -- see
            // [awakeningsFromStageSegments]'s kdoc for why the minute-derived count is
            // always zero on this device. Nights that fall back to the estimator (no
            // segments at all) keep segmentSleepEpisodes' own minute-derived figures.
            val stageAwakenings = stageSegments.takeIf { it.isNotEmpty() }?.let { awakeningsFromStageSegments(it) }
            boundedEpisode.copy(
                respiratoryRateReadings = respiratoryRate,
                stageSegments = stageSegments,
                awakenings = stageAwakenings?.count ?: boundedEpisode.awakenings,
                awakeningsDurationMinutes = stageAwakenings?.durationMinutes ?: boundedEpisode.awakeningsDurationMinutes,
            )
        }
    }

    /**
     * Rebuilds [episode] around [session]'s own boundaries -- its first segment's start and
     * its last segment's end -- instead of the minute-derived ones [segmentSleepEpisodes]
     * produced. Every figure that depends on where the night begins and ends is recomputed
     * from that new span: duration, sleep efficiency, minimum heart rate and (back in
     * [loadNights], which queries by the returned boundaries) respiratory rate. Awakenings
     * are left to [loadNights]'s own [awakeningsFromStageSegments] call, which needs the
     * clipped [StageSegment]s rather than this function's raw [session].
     *
     * Deliberately NOT read from here: the session row's own `TIMESTAMP` (the export's
     * session id, held in [Session.end]) and the blob's leading uint32 (decoded but
     * discarded by [ch.kevinjordil.helion.source.parseSleepSessionBlob]'s caller) -- both
     * sit later than the hypnogram's own last segment, and using either would reproduce the
     * exact over-run this function exists to avoid.
     *
     * Returns [episode] unchanged when it [SleepEpisode.isInProgress]: the night has not
     * finished, so even a session blob that already overlaps it cannot be trusted as the
     * final word on when it ends -- the minute-derived boundary is the freshest information
     * available, and the in-progress flag itself (set purely from minute data, before this
     * function ever runs) must keep meaning what it says.
     */
    private fun boundToDeviceSegments(episode: SleepEpisode, session: Session, allMinutes: List<MinuteSample>): SleepEpisode {
        if (episode.isInProgress) return episode
        val fellAsleepAt = session.start
        val wokeAt = session.windowEnd
        if (wokeAt <= fellAsleepAt) return episode // defensive: cannot happen for a validated session, but never worth a crash.

        val spanMinutes = allMinutes.filter { it.timestamp in fellAsleepAt..wokeAt }
        val totalSpanMinutes = (wokeAt - fellAsleepAt) / CADENCE_SECONDS + 1
        val stageSegments = clipToEpisode(session, episode.copy(fellAsleepAt = fellAsleepAt, wokeAt = wokeAt))
        val asleepSegments = stageSegments.filter { it.phase != SleepPhase.AWAKE }
        val durationAsleepMinutes = asleepSegments.sumOf { (it.endTimestamp - it.startTimestamp) / CADENCE_SECONDS + 1 }
        val minHeartRate = spanMinutes
            .filter { sample -> asleepSegments.any { sample.timestamp in it.startTimestamp..it.endTimestamp } }
            .mapNotNull { it.heartRate }
            .minOrNull()

        return episode.copy(
            date = Instant.ofEpochSecond(wokeAt).atZone(zone).toLocalDate(),
            fellAsleepAt = fellAsleepAt,
            wokeAt = wokeAt,
            durationAsleepMinutes = durationAsleepMinutes,
            sleepEfficiency = durationAsleepMinutes.toDouble() / totalSpanMinutes.toDouble(),
            minHeartRate = minHeartRate,
            minutes = spanMinutes,
        )
    }
}

/** One device sleep session, its segments grouped by [SleepStageSegment.sessionEnd] and its own overall window derived from them. */
private data class Session(val end: Long, val start: Long, val windowEnd: Long, val segments: List<SleepStageSegment>) {
    companion object {
        /** Null when [segments] is empty (should not happen for a real group, but keeps this total) or maps to no known stage. */
        fun of(segments: List<SleepStageSegment>): Session? {
            if (segments.isEmpty()) return null
            val sessionEnd = segments.first().sessionEnd
            val start = segments.minOf { it.startTimestamp }
            val windowEnd = segments.maxOf { it.endTimestamp }
            return Session(sessionEnd, start, windowEnd, segments)
        }
    }
}

/**
 * Picks, among [sessions], the one whose own window best matches [episode] -- or null when
 * none overlaps it at all.
 *
 * Matching itself is still done against [episode]'s minute-derived window (from
 * [segmentSleepEpisodes]): that window can over-run the true wake time (see
 * [SleepReader]'s own kdoc) but is otherwise the only candidate window available before a
 * session has been picked, so it remains what overlap is measured against here. Once a
 * session is chosen, [SleepReader.boundToDeviceSegments] is what actually replaces the
 * episode's boundaries with the session's own -- this function only chooses which session,
 * never adjusts anything.
 *
 * A real export can carry more than one session row for what is physically the same
 * night (a preliminary snapshot later superseded, or occasionally a stale duplicate whose
 * own recorded end time drifted hours from its segments -- see the source history for how
 * this was found). Overlap with the episode's own window is what tells those apart: the
 * best-overlapping session is chosen, and among an exact tie, the one whose own end is
 * closest to the episode's [SleepEpisode.wokeAt] -- the freshest, most trustworthy report
 * of how this particular night actually ended.
 */
private fun matchSession(episode: SleepEpisode, sessions: List<Session>): Session? =
    sessions
        .mapNotNull { session ->
            val overlap = min(session.windowEnd, episode.wokeAt) - max(session.start, episode.fellAsleepAt)
            if (overlap <= 0) null else session to overlap
        }
        .maxWithOrNull(
            compareBy(
                { (_, overlap) -> overlap },
                { (session, _) -> -abs(session.end - episode.wokeAt) },
            ),
        )
        ?.first

/** [session]'s segments, mapped to [StageSegment] and clipped to [episode]'s own span. */
private fun clipToEpisode(session: Session, episode: SleepEpisode): List<StageSegment> =
    session.segments.mapNotNull { segment ->
        val phase = devicePhaseOf(segment.stage) ?: return@mapNotNull null
        val start = max(segment.startTimestamp, episode.fellAsleepAt)
        val end = min(segment.endTimestamp, episode.wokeAt)
        if (end < start) null else StageSegment(start, end, phase)
    }
