package ch.kevinjordil.helion.activity

import ch.kevinjordil.helion.store.MinuteSample
import java.time.Instant
import java.time.ZoneId
import kotlin.math.min

/**
 * The owner's own resting heart rate and day-to-day spread, derived from his own history --
 * never an absolute constant, because a fixed bpm number tuned on one person is wrong for
 * everyone else, including a future export of this same app for someone else's wrist.
 *
 * [restingBpm] is a low percentile of all-day readings (see
 * [DetectionThresholds.restingPercentile]), not sleep-specific: this device gives no other
 * standing resting measurement while awake, and quiet stretches of an ordinary day
 * (sitting, sleeping) already dominate any reasonably sized history, so a low percentile of
 * everything lands close to true rest without needing to cross into sleep data at all.
 */
data class HeartRateBaseline(val restingBpm: Double, val spreadBpm: Double, val distinctDays: Int) {
    /**
     * The heart rate above which a minute counts as "elevated effort" for both detection
     * passes -- see [DetectionThresholds.elevationSpreadMultiplier] and
     * [DetectionThresholds.minElevationBpm] for why this is the larger of a relative and an
     * absolute margin above [restingBpm], not either alone.
     */
    fun elevatedThresholdBpm(thresholds: DetectionThresholds): Double =
        restingBpm + maxOf(thresholds.minElevationBpm, thresholds.elevationSpreadMultiplier * spreadBpm)
}

/**
 * Computes [HeartRateBaseline] from [minutes] -- typically the owner's last
 * [DetectionThresholds.baselineWindowDays] of minute samples. Returns null when fewer than
 * [DetectionThresholds.minBaselineDays] distinct calendar days carry a heart-rate reading:
 * an explicit "not enough yet" rather than a threshold guessed from a handful of hours,
 * which both detection passes must treat as "detect nothing at all" (see
 * [ActivityDetector]).
 */
fun computeHeartRateBaseline(
    minutes: List<MinuteSample>,
    zone: ZoneId,
    thresholds: DetectionThresholds = DetectionThresholds(),
): HeartRateBaseline? {
    val withHeartRate = minutes.filter { it.heartRate != null }
    if (withHeartRate.isEmpty()) return null

    val distinctDays = withHeartRate.asSequence()
        .map { Instant.ofEpochSecond(it.timestamp).atZone(zone).toLocalDate() }
        .distinct()
        .count()
    if (distinctDays < thresholds.minBaselineDays) return null

    val sorted = withHeartRate.map { it.heartRate!!.toDouble() }.sorted()
    val resting = percentile(sorted, thresholds.restingPercentile)
    val low = percentile(sorted, thresholds.spreadLowPercentile)
    val high = percentile(sorted, thresholds.spreadHighPercentile)
    val spread = maxOf(high - low, thresholds.minSpreadBpm)

    return HeartRateBaseline(restingBpm = resting, spreadBpm = spread, distinctDays = distinctDays)
}

/** Linear-interpolation percentile ([p] in `0.0..1.0`) of an already-sorted, non-empty list. Same approach as [ch.kevinjordil.helion.ui.sleep.SleepPhaseThresholds]'s own private percentile helper. */
private fun percentile(sorted: List<Double>, p: Double): Double {
    if (sorted.size == 1) return sorted[0]
    val index = p * (sorted.size - 1)
    val lower = index.toInt()
    val upper = min(lower + 1, sorted.size - 1)
    val fraction = index - lower
    return sorted[lower] + (sorted[upper] - sorted[lower]) * fraction
}
