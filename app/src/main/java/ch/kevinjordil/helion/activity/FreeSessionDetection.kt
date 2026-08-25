package ch.kevinjordil.helion.activity

import ch.kevinjordil.helion.store.MinuteSample

/** One minute, in seconds -- this device's own cadence. */
private const val CADENCE_SECONDS = 60L

/** One candidate session pass 2 found, with the heart-rate range it was based on. */
data class DetectedSession(val start: Long, val end: Long, val minHeartRate: Int, val maxHeartRate: Int)

/**
 * Pass 2: sustained elevated heart rate over [minutes], restricted to whatever [excludedRanges]
 * (a half-open `[start, end)` per range) do not already cover -- time pass 1 trimmed into a
 * candidate, and time an existing [ch.kevinjordil.helion.store.Activity] of any status
 * already occupies. This pass never looks inside an excluded range at all, so it cannot
 * propose a session overlapping one, and it cannot use an excluded range's own minutes to
 * bridge a dip on either side of it either -- see the exclusion-boundary check below.
 *
 * The merge rule mirrors [ch.kevinjordil.helion.ui.sleep.segmentSleepEpisodes]: two
 * elevated minutes join the same session when the gap between them is at most
 * [DetectionThresholds.maxDipMinutes], whether that gap is a genuine drop below the
 * elevation threshold or simply missing minute rows -- both mean "effort cannot be
 * confirmed for this short stretch", and both are tolerated the same way. This is what
 * keeps one badminton match (rallies, each separated by picking up shuttlecocks) as ONE
 * session instead of a dozen fragments, while a gap wider than that -- a genuine break
 * between two separate things he did -- still splits into two.
 *
 * A session's boundaries are its first and last elevated minute (inclusive), not the
 * dip-widened span: a dip inside a session is tolerated for merging, but never counted as
 * part of the effort itself.
 *
 * Sessions shorter than [DetectionThresholds.minFreeSessionMinutes] are dropped entirely --
 * pass 2 has no declared commitment backing it up, so it asks for a longer sustained
 * elevation than pass 1 does before proposing anything at all.
 */
fun detectFreeSessions(
    minutes: List<MinuteSample>,
    excludedRanges: List<LongRange>,
    baseline: HeartRateBaseline,
    thresholds: DetectionThresholds = DetectionThresholds(),
): List<DetectedSession> {
    val threshold = baseline.elevatedThresholdBpm(thresholds)
    val free = minutes
        .filter { sample -> excludedRanges.none { sample.timestamp in it } }
        .sortedBy { it.timestamp }
    val elevated = free.filter { it.heartRate != null && it.heartRate >= threshold }
    if (elevated.isEmpty()) return emptyList()

    val maxDipSeconds = thresholds.maxDipMinutes * CADENCE_SECONDS

    data class Building(var firstTs: Long, var lastTs: Long, val heartRates: MutableList<Int> = mutableListOf())

    val built = mutableListOf<Building>()
    var current: Building? = null

    for (sample in elevated) {
        val heartRate = sample.heartRate!!
        val building = current
        if (building == null) {
            current = Building(sample.timestamp, sample.timestamp).also { it.heartRates.add(heartRate) }
            continue
        }
        val gapSeconds = sample.timestamp - building.lastTs
        // An excluded range sitting strictly between the two minutes means real, decided
        // time separates them -- an already-settled activity, or a candidate this same
        // pass 1 run just created -- so the gap must not merge across it even when short
        // enough to otherwise tolerate.
        val crossesExclusion = excludedRanges.any { it.first in (building.lastTs + 1) until sample.timestamp }
        if (gapSeconds <= maxDipSeconds && !crossesExclusion) {
            building.lastTs = sample.timestamp
            building.heartRates.add(heartRate)
        } else {
            built.add(building)
            current = Building(sample.timestamp, sample.timestamp).also { it.heartRates.add(heartRate) }
        }
    }
    current?.let { built.add(it) }

    return built.mapNotNull { building ->
        val end = building.lastTs + CADENCE_SECONDS
        val durationMinutes = (end - building.firstTs) / CADENCE_SECONDS
        if (durationMinutes < thresholds.minFreeSessionMinutes) return@mapNotNull null
        DetectedSession(building.firstTs, end, building.heartRates.min(), building.heartRates.max())
    }
}
