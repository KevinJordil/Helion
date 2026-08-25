package ch.kevinjordil.helion.ui.sleep

import ch.kevinjordil.helion.source.DeviceSleepStage
import ch.kevinjordil.helion.source.SleepStage
import ch.kevinjordil.helion.store.MinuteSample
import kotlin.math.max
import kotlin.math.min

/**
 * An estimated sleep phase for one minute of a [SleepEpisode]. This device reports only
 * asleep/awake (see [SleepStage]) -- everything other than [AWAKE] here is a guess, never
 * a measurement. See [estimateSleepPhases] for the rule and [SleepPhaseThresholds] for its
 * numbers.
 */
enum class SleepPhase { AWAKE, LIGHT, REM, DEEP }

/** One minute's estimated phase. */
data class PhaseMinute(val timestamp: Long, val phase: SleepPhase)

/**
 * The outcome of [estimateSleepPhases]. Deliberately not "always a phase list with light
 * as the fallback": an episode with no heart-rate data, or too few asleep minutes to
 * establish its own baseline, genuinely cannot be classified, and a caller must show that
 * honestly rather than a guessed-flat night.
 */
sealed class SleepPhaseEstimate {
    data class Estimated(val minutes: List<PhaseMinute>) : SleepPhaseEstimate()
    data object NotEstimable : SleepPhaseEstimate()
}

/**
 * Every number [estimateSleepPhases] depends on, bundled the same way [SleepThresholds]
 * is, so tuning never requires touching the algorithm itself.
 */
data class SleepPhaseThresholds(
    /**
     * Fewer asleep minutes than this and the episode's own heart-rate floor/spread
     * cannot be trusted -- a handful of minutes is noise, not a baseline. Also short of
     * [SleepThresholds.minNightDurationMinutes] itself so a nap is excluded well before
     * this even runs, but this guard stands on its own for an episode built from sparse
     * fixture or test data.
     */
    val minAsleepMinutesForBaseline: Int = 30,
    /** The night's heart-rate "floor": the low percentile of asleep heart rate, not the bare minimum, so one noisy low reading cannot drag the whole baseline down. */
    val hrFloorPercentile: Double = 0.10,
    /** The night's heart-rate "ceiling" for spread purposes -- see [minHrSpreadBpm]. */
    val hrSpreadPercentile: Double = 0.90,
    /** Floor for the floor-to-ceiling spread itself, so a nearly flat night (spread near zero) does not make "near the floor" a razor's edge that misclassifies ordinary noise as elevated. */
    val minHrSpreadBpm: Double = 3.0,
    /** A minute's heart rate counts as "near the floor" when it is within this fraction of the night's own spread above [hrFloorPercentile]. */
    val nearFloorFraction: Double = 0.30,
    /** Movement percentile (of the episode's own asleep-minute intensity values) below which a minute counts as "low movement" -- deep sleep's movement bar. */
    val lowMovementPercentile: Double = 0.40,
    /** A stricter, lower movement percentile than [lowMovementPercentile] -- REM's movement bar is stillness beyond ordinary low movement. */
    val veryLowMovementPercentile: Double = 0.15,
    /**
     * Minutes per smoothing window: both the unit of majority vote and, by construction,
     * the shortest run a phase can realistically survive in -- see [smoothPhases]. Five
     * minutes absorbs single-minute sensor noise without erasing genuinely short REM or
     * deep stretches.
     */
    val smoothingWindowMinutes: Int = 5,
)

/**
 * Estimates a sleep phase for every minute of [minutes] (an episode's own span, asleep and
 * awake alike -- see [SleepEpisode.minutes]).
 *
 * The rule, deliberately simple and stated here so it stays honest about being a
 * heuristic, not a measurement:
 * - A minute already marked AWAKE stays [SleepPhase.AWAKE].
 * - Otherwise, establish the *episode's own* heart-rate floor (a low percentile, see
 *   [SleepPhaseThresholds.hrFloorPercentile]) and spread up to a high percentile
 *   ([SleepPhaseThresholds.hrSpreadPercentile]) from its asleep minutes only. Everything
 *   below is judged relative to that one night's own numbers, never an absolute bpm --
 *   resting heart rate varies hugely between people and between nights, so an absolute
 *   threshold would be meaningless.
 * - Low movement (intensity below [SleepPhaseThresholds.lowMovementPercentile] of the
 *   night's own intensity distribution) AND heart rate near the floor
 *   ([SleepPhaseThresholds.nearFloorFraction] of the spread) -> [SleepPhase.DEEP].
 * - Very low movement (the stricter [SleepPhaseThresholds.veryLowMovementPercentile])
 *   BUT heart rate *not* near the floor -> [SleepPhase.REM]: the body is still, but the
 *   heart is not settled the way it is in deep sleep.
 * - Everything else asleep -> [SleepPhase.LIGHT], including any minute missing heart rate
 *   or intensity: an unclassifiable minute defaults to the least specific asleep phase
 *   rather than being guessed into deep or REM.
 * - The result is smoothed (see [smoothPhases]) so phases cannot flip minute to minute --
 *   unsmoothed output would be visual noise, not information.
 *
 * Returns [SleepPhaseEstimate.NotEstimable] when the episode has no heart-rate data at
 * all, or too few asleep minutes to establish a baseline
 * ([SleepPhaseThresholds.minAsleepMinutesForBaseline]) -- see that class's kdoc.
 */
fun estimateSleepPhases(
    minutes: List<MinuteSample>,
    thresholds: SleepPhaseThresholds = SleepPhaseThresholds(),
): SleepPhaseEstimate {
    val sorted = minutes.sortedBy { it.timestamp }
    val asleep = sorted.filter { it.sleepStage == SleepStage.ASLEEP }
    val asleepHeartRates = asleep.mapNotNull { it.heartRate?.toDouble() }
    if (asleepHeartRates.isEmpty()) return SleepPhaseEstimate.NotEstimable
    if (asleep.size < thresholds.minAsleepMinutesForBaseline) return SleepPhaseEstimate.NotEstimable

    val sortedHeartRates = asleepHeartRates.sorted()
    val hrFloor = percentile(sortedHeartRates, thresholds.hrFloorPercentile)
    val hrHigh = percentile(sortedHeartRates, thresholds.hrSpreadPercentile)
    val hrSpread = max(hrHigh - hrFloor, thresholds.minHrSpreadBpm)
    val nearFloorMaxAboveFloor = thresholds.nearFloorFraction * hrSpread

    val sortedIntensities = asleep.mapNotNull { it.intensity?.toDouble() }.sorted()
    val lowMovementThreshold = sortedIntensities.takeIf { it.isNotEmpty() }?.let { percentile(it, thresholds.lowMovementPercentile) }
    val veryLowMovementThreshold = sortedIntensities.takeIf { it.isNotEmpty() }?.let { percentile(it, thresholds.veryLowMovementPercentile) }

    val raw = sorted.map { sample ->
        val phase = when {
            sample.sleepStage != SleepStage.ASLEEP -> SleepPhase.AWAKE
            sample.heartRate == null || sample.intensity == null || lowMovementThreshold == null -> SleepPhase.LIGHT
            else -> {
                val aboveFloor = sample.heartRate - hrFloor
                val nearFloor = aboveFloor <= nearFloorMaxAboveFloor
                val lowMovement = sample.intensity <= lowMovementThreshold
                val veryLowMovement = sample.intensity <= (veryLowMovementThreshold ?: lowMovementThreshold)
                when {
                    lowMovement && nearFloor -> SleepPhase.DEEP
                    veryLowMovement && !nearFloor -> SleepPhase.REM
                    else -> SleepPhase.LIGHT
                }
            }
        }
        PhaseMinute(sample.timestamp, phase)
    }

    return SleepPhaseEstimate.Estimated(smoothPhases(raw, thresholds.smoothingWindowMinutes))
}

/**
 * Majority-vote smoothing: each minute becomes whichever phase is most common in the
 * [windowMinutes]-wide window centred on it, which is what keeps a phase from flipping
 * for a single noisy minute and, as a side effect, is exactly what imposes a minimum
 * segment length -- a run shorter than about half the window cannot out-vote its
 * neighbours. Minutes already [SleepPhase.AWAKE] are left alone (a confirmed awakening is
 * not something to smooth away) and are excluded from their neighbours' votes.
 */
private fun smoothPhases(minutes: List<PhaseMinute>, windowMinutes: Int): List<PhaseMinute> {
    if (windowMinutes <= 1) return minutes
    val half = windowMinutes / 2
    return minutes.mapIndexed { index, original ->
        if (original.phase == SleepPhase.AWAKE) return@mapIndexed original
        val from = max(0, index - half)
        val to = min(minutes.size - 1, index + half)
        val window = minutes.subList(from, to + 1).filter { it.phase != SleepPhase.AWAKE }
        if (window.isEmpty()) return@mapIndexed original
        val majority = window.groupingBy { it.phase }.eachCount().maxByOrNull { it.value }!!.key
        PhaseMinute(original.timestamp, majority)
    }
}

/** Linear-interpolation percentile ([p] in `0.0..1.0`) of an already-sorted, non-empty list. */
private fun percentile(sorted: List<Double>, p: Double): Double {
    if (sorted.size == 1) return sorted[0]
    val index = p * (sorted.size - 1)
    val lower = index.toInt()
    val upper = min(lower + 1, sorted.size - 1)
    val fraction = index - lower
    return sorted[lower] + (sorted[upper] - sorted[lower]) * fraction
}

/**
 * Total minutes spent in each of [SleepPhase.DEEP], [SleepPhase.REM] and
 * [SleepPhase.LIGHT] across an estimated phase track. [SleepPhase.AWAKE] is excluded: that
 * time is already surfaced via [SleepEpisode.awakenings] and does not belong in a phase
 * breakdown.
 */
fun sleepPhaseBreakdown(minutes: List<PhaseMinute>): Map<SleepPhase, Int> =
    minutes.asSequence()
        .filter { it.phase != SleepPhase.AWAKE }
        .groupingBy { it.phase }
        .eachCount()

/**
 * One contiguous stretch of a single stage over an episode's own span, as reported by the
 * device itself -- see [ch.kevinjordil.helion.source.parseSleepSessionBlob]. [startTimestamp]
 * and [endTimestamp] are both Unix seconds and both inclusive, matching
 * [ch.kevinjordil.helion.store.SleepStageSegment].
 */
data class StageSegment(val startTimestamp: Long, val endTimestamp: Long, val phase: SleepPhase)

/**
 * Maps the device's own stage type code (see
 * [ch.kevinjordil.helion.source.DeviceSleepStage]) to [SleepPhase]. Returns null for a code
 * this app does not know how to display, so a caller can drop that segment rather than
 * guess -- [parseSleepSessionBlob] already rejects a blob with an unknown type outright, so
 * in practice this is never null for a segment that made it this far; it stays defensive
 * for the same reason [SleepReader] does not otherwise trust export data unconditionally.
 */
fun devicePhaseOf(deviceStage: Int): SleepPhase? = when (deviceStage) {
    DeviceSleepStage.LIGHT -> SleepPhase.LIGHT
    DeviceSleepStage.DEEP -> SleepPhase.DEEP
    DeviceSleepStage.AWAKE -> SleepPhase.AWAKE
    DeviceSleepStage.REM -> SleepPhase.REM
    else -> null
}

/**
 * Where a [SleepEpisode]'s displayed phase track actually came from -- the one thing a
 * caller must never blur, because only [Estimated] is a guess.
 *
 * [Measured] wins whenever [SleepEpisode.stageSegments] is non-empty (see
 * [resolveSleepPhases]): those minutes came from the device's own hypnogram, already
 * validated by [ch.kevinjordil.helion.source.parseSleepSessionBlob] before they ever
 * reached the store, so there is nothing left to hedge about them in the UI -- no
 * "estimé" label, no italics, nothing that would put a measurement and a guess on equal
 * footing.
 */
sealed class SleepPhaseSource {
    data class Measured(val minutes: List<PhaseMinute>) : SleepPhaseSource()
    data class Estimated(val minutes: List<PhaseMinute>) : SleepPhaseSource()
    data object NotEstimable : SleepPhaseSource()
}

/**
 * Resolves [episode]'s displayed phase track: the device's own segments when
 * [SleepEpisode.stageSegments] carries any (see [SleepReader] for how those are matched to
 * an episode), falling back to [estimateSleepPhases] only when there are none -- a night
 * with no session blob at all, or whose blob failed
 * [ch.kevinjordil.helion.source.parseSleepSessionBlob]'s validation upstream and so never
 * produced any segments in the first place. This is the one place that decision is made;
 * every caller (the hypnogram, the breakdown, the night chart overlay) goes through this
 * instead of choosing for itself, so they can never disagree about which source is in use
 * for a given night.
 */
fun resolveSleepPhases(episode: SleepEpisode): SleepPhaseSource {
    if (episode.stageSegments.isNotEmpty()) {
        return SleepPhaseSource.Measured(measuredPhaseMinutes(episode))
    }
    return when (val estimate = estimateSleepPhases(episode.minutes)) {
        is SleepPhaseEstimate.Estimated -> SleepPhaseSource.Estimated(estimate.minutes)
        SleepPhaseEstimate.NotEstimable -> SleepPhaseSource.NotEstimable
    }
}

/**
 * One [PhaseMinute] per minute of [episode]'s own span, looked up against its
 * [SleepEpisode.stageSegments]. A minute the device's segments do not cover (the episode's
 * own minute-derived boundaries and the session's rarely disagree by more than a few
 * minutes, but they are not guaranteed to line up exactly -- see [SleepReader]) falls back
 * to that single minute's own coarse ASLEEP/AWAKE reading rather than being guessed into a
 * specific asleep phase: [SleepPhase.AWAKE] when the raw minute says so, [SleepPhase.LIGHT]
 * otherwise, the same "least specific asleep phase" default [estimateSleepPhases] itself
 * uses for a minute it cannot classify.
 */
private fun measuredPhaseMinutes(episode: SleepEpisode): List<PhaseMinute> {
    val byMinute = episode.minutes.associateBy { it.timestamp }
    val segments = episode.stageSegments.sortedBy { it.startTimestamp }
    var segmentIndex = 0

    var timestamp = episode.fellAsleepAt
    val result = mutableListOf<PhaseMinute>()
    while (timestamp <= episode.wokeAt) {
        while (segmentIndex < segments.size && segments[segmentIndex].endTimestamp < timestamp) segmentIndex++
        val covering = segments.getOrNull(segmentIndex)?.takeIf { timestamp in it.startTimestamp..it.endTimestamp }
        val phase = covering?.phase ?: run {
            val rawStage = byMinute[timestamp]?.sleepStage
            if (rawStage == SleepStage.ASLEEP) SleepPhase.LIGHT else SleepPhase.AWAKE
        }
        result.add(PhaseMinute(timestamp, phase))
        timestamp += 60L
    }
    return result
}
