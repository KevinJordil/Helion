package ch.kevinjordil.helion.ui.sleep

import ch.kevinjordil.helion.source.SleepStage
import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.ui.metric.Reading
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A sleep episode is either a night's sleep or a nap -- see [SleepThresholds] for how that line is drawn. */
enum class SleepEpisodeKind { NIGHT, NAP }

/**
 * One contiguous stretch of sleep, already merged across brief awakenings and short data
 * gaps (see [segmentSleepEpisodes]).
 *
 * [date] is the local calendar date this episode is attributed to -- the date it *ends*
 * on, not the date it starts on: a Monday-23:30-to-Tuesday-07:00 night is "Tuesday's
 * night", the one the owner actually checks against on Tuesday morning.
 *
 * [isInProgress] is true when the most recent minute Helion has ever recorded is itself
 * part of this episode and is still ASLEEP: as far as the archive knows, the night has not
 * ended yet, and every duration/efficiency figure on it is provisional, not a final count
 * for the night. See [segmentSleepEpisodes]'s kdoc.
 *
 * [hasDataGap] is true when at least one of the interruptions merged into this episode was
 * (at least in part) missing minute rows -- the strap not worn, or a sync that missed a
 * stretch -- rather than a confirmed AWAKE reading. Duration and efficiency are computed
 * the same way regardless, but a caller MUST surface this rather than presenting the
 * number as a trustworthy final count: Helion genuinely does not know what happened during
 * missing minutes, only that something is missing.
 *
 * [minutes] is every minute sample (asleep, awake, or in between) spanning
 * `[fellAsleepAt, wokeAt]`, the source for the night's heart-rate chart and estimated
 * sleep phases -- see [ch.kevinjordil.helion.ui.sleep.NightChartSection] and
 * [estimateSleepPhases].
 */
data class SleepEpisode(
    val date: LocalDate,
    val kind: SleepEpisodeKind,
    val fellAsleepAt: Long,
    val wokeAt: Long,
    val isInProgress: Boolean,
    val hasDataGap: Boolean,
    val durationAsleepMinutes: Long,
    val awakenings: Int,
    val awakeningsDurationMinutes: Long,
    val sleepEfficiency: Double,
    val minHeartRate: Int?,
    val minutes: List<MinuteSample>,
    /**
     * Every respiratory rate point over the episode's span, from the `respiratory_rate`
     * point series -- a different table on a different cadence than the minute samples
     * above, so [segmentSleepEpisodes] never sets this; it is filled in afterwards by
     * whichever caller also has access to [ch.kevinjordil.helion.store.PointSample] data
     * (see [SleepReader]). Sorted ascending by timestamp, for [SleepScreen]'s own chart.
     */
    val respiratoryRateReadings: List<Reading> = emptyList(),
) {
    /** Average of [respiratoryRateReadings], or null when the night has none. */
    val avgRespiratoryRate: Double?
        get() = respiratoryRateReadings.takeIf { it.isNotEmpty() }?.let { values -> values.sumOf { it.value } / values.size }
}

/**
 * Every threshold [segmentSleepEpisodes] and its classification depend on, bundled so a
 * future Réglages screen can hand in the owner's own preferences instead of these
 * defaults, without segmentation itself needing to change.
 */
data class SleepThresholds(
    /**
     * A recorded AWAKE stretch, or a stretch of missing minute rows, up to this long does
     * not split one episode into two. Chosen from what actually shows up in a real night:
     * a trip to the bathroom is a handful of minutes, not tens of minutes: 20 minutes is
     * generous enough to absorb that without also being long enough to stitch together two
     * genuinely separate sleep periods (an afternoon nap and the following night, say) into
     * one nonsensical episode.
     */
    val maxBriefAwakeningMinutes: Long = 20,

    /**
     * A stretch of *missing* minute rows (not a confirmed AWAKE reading) at least this long,
     * found anywhere inside an otherwise-merged episode, marks it [SleepEpisode.hasDataGap].
     * This device reports one row per minute continuously while worn -- a real export shows
     * nothing worse than an isolated two-minute hiccup during normal use -- so five minutes
     * of missing data is already outside that normal jitter and means the strap was
     * genuinely off-wrist or a sync missed a stretch, not a rounding artefact.
     */
    val minSuspiciousDataGapMinutes: Long = 5,

    /**
     * An episode shorter than this, even one that starts inside the night window, is
     * treated as a nap rather than a (very short) night. Three hours is short for any
     * night's sleep, so it clears ordinary short nights while still excluding a doze.
     */
    val minNightDurationMinutes: Long = 180,

    /**
     * An episode counts as a night only if it *starts* at or after this local hour (or
     * before [nightWindowEndHour]) -- see [isWithinNightWindow]. 20:00 is late enough that
     * it never catches an ordinary afternoon or early-evening nap.
     */
    val nightWindowStartHour: Int = 20,

    /**
     * The other edge of the night window: an episode starting at or after midnight but
     * before this local hour still counts as a night start (a late bedtime), while one
     * starting at, say, 8am plainly does not.
     */
    val nightWindowEndHour: Int = 4,
) {
    /** Whether a night beginning at [localHour] (0..23) falls inside the night window. */
    fun isWithinNightWindow(localHour: Int): Boolean =
        localHour >= nightWindowStartHour || localHour < nightWindowEndHour
}

/** One minute, in seconds -- the device's own cadence, and the unit every duration here is counted in. */
private const val CADENCE_SECONDS = 60L

/**
 * Groups [minutes] (any order, any stage, not necessarily contiguous) into
 * [SleepEpisode]s. [minutes] should be sorted ascending by timestamp already for
 * reasonable performance, but this does not assume it.
 *
 * The algorithm:
 * 1. Take every ASLEEP-flagged minute, in order.
 * 2. Two consecutive ASLEEP minutes merge into the same episode when the gap between them
 *    is at most [SleepThresholds.maxBriefAwakeningMinutes] -- regardless of whether that
 *    gap is a confirmed AWAKE reading or simply missing rows; both mean "we cannot confirm
 *    he was asleep for this short stretch", and both are tolerated the same way so a brief
 *    awakening and a brief sync hiccup are not treated as two different failure modes by
 *    the merge logic itself.
 * 3. A gap larger than that ends the episode: what follows, if anything, starts a new one.
 * 4. Each merged gap that is at least [SleepThresholds.minSuspiciousDataGapMinutes] of
 *    genuinely *missing* data (not a recorded AWAKE minute) marks the whole episode
 *    [SleepEpisode.hasDataGap].
 * 5. [SleepEpisode.isInProgress] is set when an episode's last merged ASLEEP minute is
 *    also the very last minute Helion has ever recorded, and that minute is ASLEEP: no
 *    later reading -- awake or otherwise -- has arrived to confirm the night actually
 *    ended. [now] is accepted for symmetry with the rest of the app's reading functions
 *    but is not otherwise used: whether the *archive* has seen a wake-up is the only
 *    signal this device gives, and staleness of the sync itself is a separate concern
 *    (see the freshness line on Accueil), not something sleep segmentation re-derives.
 * 6. [SleepEpisode.kind] normally requires both the night window (see
 *    [SleepThresholds.isWithinNightWindow]) and [SleepThresholds.minNightDurationMinutes]
 *    to be met. An in-progress episode is the one exception: its final duration is not
 *    known yet, so only the window is checked -- a night two hours old when the owner
 *    opens the app at 1am is still a night, not (yet) a nap by a duration test it has not
 *    had time to pass.
 */
fun segmentSleepEpisodes(
    minutes: List<MinuteSample>,
    zone: ZoneId,
    now: Long,
    thresholds: SleepThresholds = SleepThresholds(),
): List<SleepEpisode> {
    val sorted = minutes.sortedBy { it.timestamp }
    val asleep = sorted.filter { it.sleepStage == SleepStage.ASLEEP }
    if (asleep.isEmpty()) return emptyList()

    val coverageGaps = coverageGapsMinutes(sorted)
    val maxGapSeconds = thresholds.maxBriefAwakeningMinutes * CADENCE_SECONDS

    data class Building(
        var firstTs: Long,
        var lastTs: Long,
        var awakenings: Int = 0,
        var awakeningsDurationMinutes: Long = 0,
        var hasDataGap: Boolean = false,
        val asleepSamples: MutableList<MinuteSample> = mutableListOf(),
    )

    val built = mutableListOf<Building>()
    var current: Building? = null

    for (sample in asleep) {
        val building = current
        if (building == null) {
            current = Building(firstTs = sample.timestamp, lastTs = sample.timestamp).also {
                it.asleepSamples.add(sample)
            }
            continue
        }
        val gapSeconds = sample.timestamp - building.lastTs
        if (gapSeconds <= maxGapSeconds) {
            if (gapSeconds > CADENCE_SECONDS) {
                building.awakenings += 1
                building.awakeningsDurationMinutes += (gapSeconds - CADENCE_SECONDS) / CADENCE_SECONDS
                val suspicious = coverageGaps.any { gap ->
                    gap.afterTimestamp >= building.lastTs &&
                        gap.beforeTimestamp <= sample.timestamp &&
                        gap.missingMinutes >= thresholds.minSuspiciousDataGapMinutes
                }
                if (suspicious) building.hasDataGap = true
            }
            building.lastTs = sample.timestamp
            building.asleepSamples.add(sample)
        } else {
            built.add(building)
            current = Building(firstTs = sample.timestamp, lastTs = sample.timestamp).also {
                it.asleepSamples.add(sample)
            }
        }
    }
    current?.let { built.add(it) }

    val lastRecordedMinute = sorted.last()

    return built.map { building ->
        val fellAsleepAt = building.firstTs
        val wokeAt = building.lastTs
        val isInProgress = lastRecordedMinute.timestamp == wokeAt && lastRecordedMinute.sleepStage == SleepStage.ASLEEP
        val spanMinutes = sorted.filter { it.timestamp in fellAsleepAt..wokeAt }
        val totalSpanMinutes = (wokeAt - fellAsleepAt) / CADENCE_SECONDS + 1
        val durationAsleepMinutes = building.asleepSamples.size.toLong()
        val startHour = Instant.ofEpochSecond(fellAsleepAt).atZone(zone).hour
        val withinWindow = thresholds.isWithinNightWindow(startHour)
        val kind = when {
            !withinWindow -> SleepEpisodeKind.NAP
            isInProgress -> SleepEpisodeKind.NIGHT
            totalSpanMinutes >= thresholds.minNightDurationMinutes -> SleepEpisodeKind.NIGHT
            else -> SleepEpisodeKind.NAP
        }
        SleepEpisode(
            date = Instant.ofEpochSecond(wokeAt).atZone(zone).toLocalDate(),
            kind = kind,
            fellAsleepAt = fellAsleepAt,
            wokeAt = wokeAt,
            isInProgress = isInProgress,
            hasDataGap = building.hasDataGap,
            durationAsleepMinutes = durationAsleepMinutes,
            awakenings = building.awakenings,
            awakeningsDurationMinutes = building.awakeningsDurationMinutes,
            sleepEfficiency = durationAsleepMinutes.toDouble() / totalSpanMinutes.toDouble(),
            minHeartRate = building.asleepSamples.mapNotNull { it.heartRate }.minOrNull(),
            minutes = spanMinutes,
        )
    }
}

private data class CoverageGap(val afterTimestamp: Long, val beforeTimestamp: Long, val missingMinutes: Long)

/**
 * Every stretch of the sorted minute timeline where rows are simply absent -- neither
 * ASLEEP nor AWAKE was recorded, because nothing was recorded at all. This is what
 * [segmentSleepEpisodes] consults to tell a genuine data gap apart from a confirmed
 * awakening when both happen to be short enough to merge across.
 */
private fun coverageGapsMinutes(sorted: List<MinuteSample>): List<CoverageGap> {
    if (sorted.size < 2) return emptyList()
    val gaps = mutableListOf<CoverageGap>()
    for (i in 1 until sorted.size) {
        val previous = sorted[i - 1].timestamp
        val next = sorted[i].timestamp
        val deltaMinutes = (next - previous) / CADENCE_SECONDS
        if (deltaMinutes > 1) {
            gaps.add(CoverageGap(previous, next, deltaMinutes - 1))
        }
    }
    return gaps
}
