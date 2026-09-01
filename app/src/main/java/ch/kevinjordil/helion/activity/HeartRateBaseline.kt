package ch.kevinjordil.helion.activity

import ch.kevinjordil.helion.store.MinuteSample
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/**
 * The owner's own resting heart rate and observed ceiling, derived from his own history --
 * never an absolute constant, because a fixed bpm number tuned on one person is wrong for
 * everyone else, including a future export of this same app for someone else's wrist.
 *
 * [restingBpm] is a low percentile of all-day readings (see
 * [DetectionThresholds.restingPercentile]), not sleep-specific: this device gives no other
 * standing resting measurement while awake, and quiet stretches of an ordinary day
 * (sitting, sleeping) already dominate any reasonably sized history, so a low percentile of
 * everything lands close to true rest without needing to cross into sleep data at all.
 *
 * [maxBpm] is simply the highest reading in the same window -- unlike [restingBpm] it is
 * deliberately not smoothed by a percentile, because the two thresholds derived from it
 * (see below) need to reach genuine peak effort, and this device's one known corruption
 * mode (a 255 "no reading" sentinel) is already filtered out before a sample ever reaches
 * this class -- see `ExportReader`'s `VALID_HEART_RATE` bound.
 */
data class HeartRateBaseline(val restingBpm: Double, val maxBpm: Double, val distinctDays: Int) {
    /** (observed max - resting), floored by [DetectionThresholds.minRangeBpm] -- see its kdoc. */
    private fun range(thresholds: DetectionThresholds): Double = max(maxBpm - restingBpm, thresholds.minRangeBpm)

    /**
     * The heart rate a minute must reach -- and, per [DetectionThresholds.minEntrySustainMinutes],
     * hold -- to *enter* a session. The higher half of the hysteresis pair with
     * [floorThresholdBpm]; see [DetectionThresholds.enterFraction] for the derivation.
     */
    fun enterThresholdBpm(thresholds: DetectionThresholds): Double =
        restingBpm + thresholds.enterFraction * range(thresholds)

    /**
     * The heart rate a minute must fall below -- for longer than
     * [DetectionThresholds.dipToleranceMinutes] -- to *end* an already-started session, or
     * (for pass 1) the boundary a slot occurrence is trimmed to. The lower half of the
     * hysteresis pair with [enterThresholdBpm]; see [DetectionThresholds.floorFraction] for
     * the derivation.
     */
    fun floorThresholdBpm(thresholds: DetectionThresholds): Double =
        restingBpm + thresholds.floorFraction * range(thresholds)
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
    val max = sorted.last()

    return HeartRateBaseline(restingBpm = resting, maxBpm = max, distinctDays = distinctDays)
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
