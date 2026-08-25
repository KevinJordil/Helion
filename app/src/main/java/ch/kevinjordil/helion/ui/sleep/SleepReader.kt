package ch.kevinjordil.helion.ui.sleep

import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.store.SleepStageSegment
import ch.kevinjordil.helion.ui.metric.Reading
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** How far back nights are read for the history list and the personal baseline behind it. */
private const val LOOKBACK_DAYS = 30L

private const val RESPIRATORY_RATE_SERIES = "respiratory_rate"

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
 * Episode boundaries are deliberately still minute-derived, not session-derived: see
 * [matchSession]'s kdoc for why an episode is the one and only notion of "a night", and a
 * session is only ever consulted for its stage segments, never for when the night began
 * or ended.
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
            val respiratoryRate = db.pointSamples()
                .between(RESPIRATORY_RATE_SERIES, episode.fellAsleepAt, episode.wokeAt)
                .map { Reading(it.timestamp, it.value) }
                .sortedBy { it.timestamp }
            val stageSegments = matchSession(episode, sessions)?.let { clipToEpisode(it, episode) } ?: emptyList()
            episode.copy(respiratoryRateReadings = respiratoryRate, stageSegments = stageSegments)
        }
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
 * The episode's boundaries (from minute-level ASLEEP/AWAKE segmentation, see
 * [segmentSleepEpisodes]) remain the single source of truth for "when did the night start
 * and end", its duration, efficiency, awakenings, in-progress and data-gap flags: none of
 * that exists in a session blob, and minute-level segmentation already has to handle cases
 * (an in-progress night, a data gap, a nap) a session's own boundaries say nothing about.
 * A session is only ever asked for stage segments to paint onto an episode that minute
 * segmentation already decided exists -- never the other way around -- so there is exactly
 * one notion of "last night" (the episode), and sessions are strictly an enrichment of it.
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
