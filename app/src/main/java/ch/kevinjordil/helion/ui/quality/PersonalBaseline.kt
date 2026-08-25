package ch.kevinjordil.helion.ui.quality

import ch.kevinjordil.helion.ui.metric.Reading
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * A robust summary of a metric's recent personal history: [median] rather than a mean, so
 * one bad night or one outlier reading does not drag the whole baseline with it, and
 * [spread] -- the median absolute deviation, scaled to be comparable to a standard
 * deviation under a roughly normal distribution -- for the same reason a plain min/max
 * range would not be: a single extreme reading would otherwise blow the range out and make
 * everything else look artificially "usual".
 *
 * [distinctDays] is what [computeBaseline] gates on, not raw sample count: a metric that
 * reports every minute (heart rate) would otherwise "have enough history" after a single
 * busy hour, while one that reports every few hours (PAI) would take unreasonably long --
 * neither reflects what "enough personal history" actually means here, which is measured
 * in days lived with the strap, not in row count.
 */
data class Baseline(val median: Double, val spread: Double, val distinctDays: Int)

/** Position relative to a personal baseline, or an honest admission there is not one yet. */
sealed interface PersonalBaseline {
    /** Fewer than [MIN_BASELINE_DAYS] distinct days of history: too early to say anything. */
    data object InsufficientHistory : PersonalBaseline

    /** [isNotable] is the only thing that may render as amber -- see [Position]'s kdoc. */
    data class Placed(val position: Position, val isNotable: Boolean) : PersonalBaseline
}

/** Distinct days of history required before a baseline is computed at all, rather than guessed at. */
const val MIN_BASELINE_DAYS = 5

/** Within this many scaled-MAD of the median counts as [Position.USUAL]. */
private const val USUAL_BAND = 1.0

/** Beyond this many scaled-MAD of the median is notable enough to earn the amber accent. */
private const val NOTABLE_BAND = 2.0

/** Scales a median absolute deviation to be comparable to a standard deviation under a roughly normal distribution. */
private const val MAD_TO_SIGMA = 1.4826

/**
 * Computes [Baseline] from [history] -- typically a metric's last ~30 days of readings,
 * exactly what [ch.kevinjordil.helion.ui.metric.MetricReader] already returns for
 * `Range.MONTH`, so this does not re-implement any store access or day-bucketing of its
 * own. Returns null when fewer than [MIN_BASELINE_DAYS] distinct calendar days are
 * represented -- an explicit "not enough yet" rather than a baseline computed from
 * whatever happens to be there.
 */
fun computeBaseline(history: List<Reading>, zone: ZoneId = ZoneId.systemDefault()): Baseline? {
    if (history.isEmpty()) return null
    val distinctDays = history.map { Instant.ofEpochSecond(it.timestamp).atZone(zone).toLocalDate() }.distinct().size
    if (distinctDays < MIN_BASELINE_DAYS) return null

    val values = history.map { it.value }.sorted()
    val median = median(values)
    val deviations = values.map { abs(it - median) }
    val mad = median(deviations)

    return Baseline(median = median, spread = mad * MAD_TO_SIGMA, distinctDays = distinctDays)
}

/**
 * Places [current] against [baseline]. Null [baseline] (from [computeBaseline] returning
 * null) always yields [PersonalBaseline.InsufficientHistory] -- never a guess.
 *
 * A zero [Baseline.spread] (every recent reading identical) falls out of the same
 * comparison rather than needing a special case: both thresholds become zero, so any real
 * difference from the median is immediately notable, and an exact match is still
 * [Position.USUAL].
 */
fun placeAgainstBaseline(current: Double, baseline: Baseline?): PersonalBaseline {
    if (baseline == null) return PersonalBaseline.InsufficientHistory

    val diff = current - baseline.median
    val absDiff = abs(diff)
    val usualThreshold = USUAL_BAND * baseline.spread
    val notableThreshold = NOTABLE_BAND * baseline.spread

    val position = when {
        absDiff <= usualThreshold -> Position.USUAL
        diff < 0 -> Position.BELOW
        else -> Position.ABOVE
    }
    return PersonalBaseline.Placed(position, isNotable = absDiff > notableThreshold)
}

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
}
