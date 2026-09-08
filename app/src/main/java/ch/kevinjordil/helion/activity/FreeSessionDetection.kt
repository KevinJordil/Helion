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
 * Two thresholds with hysteresis, not one -- see [HeartRateBaseline.enterThresholdBpm] and
 * [HeartRateBaseline.floorThresholdBpm]:
 *
 * 1. Minutes are grouped into raw blocks wherever heart rate is at or above the lower
 *    *floor* threshold, merging across a gap (a genuine dip below floor, or simply missing
 *    minute rows -- both mean "effort cannot be confirmed for this short stretch", and both
 *    are tolerated the same way) of at most [DetectionThresholds.dipToleranceMinutes]. This
 *    is what keeps one badminton tournament (five matches, each separated by a real
 *    changeover) as ONE session instead of five fragments, while a gap wider than that -- a
 *    genuine break between two separate things he did -- still splits into two.
 * 2. A block only survives as a candidate if, somewhere inside it, heart rate held at or
 *    above the higher *enter* threshold for at least
 *    [DetectionThresholds.minEntrySustainMinutes] *consecutive* minutes (no gap tolerated
 *    for this check specifically -- it exists to confirm real effort actually happened, not
 *    to be as forgiving as the floor merge above). This is what stops a long, mildly
 *    elevated evening -- a hot night, a stressful stretch -- that never actually reaches
 *    genuine exertion from qualifying just because it stayed above the (lower, deliberately
 *    forgiving) floor for a long time.
 * 3. A surviving block's start is its first floor-crossing minute (inclusive) -- this keeps
 *    the warm-up ahead of the confirmed effort inside the session, exactly as observed in
 *    the training block real data was checked against, rather than cutting the session down
 *    to only the highest-intensity minutes. Its end, however, is trimmed back from the
 *    block's last floor-crossing minute to its last minute at or above the separate, higher
 *    *end* threshold ([HeartRateBaseline.endThresholdBpm]) -- see
 *    [DetectionThresholds.endTrimFraction] for why the trailing edge does not get the same
 *    treatment as the leading one: a floor-tolerant tail after real effort stops (changing
 *    room, shower) must not read as part of the session, even though a floor-tolerant lead-in
 *    before it starts (warm-up) should.
 *
 * A session's boundaries are its first elevated minute and its last minute of confirmed
 * effort (inclusive), not the dip-widened span: a dip inside a session is tolerated for
 * merging, but never counted as part of the effort itself, and neither is a lingering tail
 * past the point effort actually stopped.
 *
 * Sessions shorter than [DetectionThresholds.minFreeSessionMinutes] are dropped entirely --
 * pass 2 has no declared commitment backing it up the way pass 1 does, so it asks for a
 * longer sustained elevation, on top of the entry confirmation above, before proposing
 * anything at all.
 */
fun detectFreeSessions(
    minutes: List<MinuteSample>,
    excludedRanges: List<LongRange>,
    baseline: HeartRateBaseline,
    thresholds: DetectionThresholds = DetectionThresholds(),
): List<DetectedSession> {
    val enterThreshold = baseline.enterThresholdBpm(thresholds)
    val floorThreshold = baseline.floorThresholdBpm(thresholds)
    val endThreshold = baseline.endThresholdBpm(thresholds)
    val free = minutes
        .filter { sample -> excludedRanges.none { sample.timestamp in it } }
        .sortedBy { it.timestamp }
    val aboveFloor = free.filter { it.heartRate != null && it.heartRate >= floorThreshold }
    if (aboveFloor.isEmpty()) return emptyList()

    val dipToleranceSeconds = thresholds.dipToleranceMinutes * CADENCE_SECONDS

    data class Building(var firstTs: Long, var lastTs: Long, val samples: MutableList<MinuteSample> = mutableListOf())

    val built = mutableListOf<Building>()
    var current: Building? = null

    for (sample in aboveFloor) {
        val building = current
        if (building == null) {
            current = Building(sample.timestamp, sample.timestamp).also { it.samples.add(sample) }
            continue
        }
        val gapSeconds = sample.timestamp - building.lastTs
        // An excluded range sitting strictly between the two minutes means real, decided
        // time separates them -- an already-settled activity, or a candidate this same
        // pass 1 run just created -- so the gap must not merge across it even when short
        // enough to otherwise tolerate.
        val crossesExclusion = excludedRanges.any { it.first in (building.lastTs + 1) until sample.timestamp }
        if (gapSeconds <= dipToleranceSeconds && !crossesExclusion) {
            building.lastTs = sample.timestamp
            building.samples.add(sample)
        } else {
            built.add(building)
            current = Building(sample.timestamp, sample.timestamp).also { it.samples.add(sample) }
        }
    }
    current?.let { built.add(it) }

    return built.mapNotNull { building ->
        val end = building.lastTs + CADENCE_SECONDS
        val durationMinutes = (end - building.firstTs) / CADENCE_SECONDS
        if (durationMinutes < thresholds.minFreeSessionMinutes) return@mapNotNull null
        if (!hasSustainedEntry(building.samples, enterThreshold, thresholds.minEntrySustainMinutes)) return@mapNotNull null

        // Trim the trailing tail back to the last minute of genuine effort -- see
        // DetectionThresholds.endTrimFraction. The entry-sustain check just above already
        // guarantees at least one minute in this block reaches enterThreshold, which sits
        // above endThreshold, so `lastOrNull` here always finds a match; the fallback to the
        // untrimmed `building.lastTs` only matters in principle, never in practice for this pass.
        val trimmedLastTs = building.samples.lastOrNull { (it.heartRate ?: 0) >= endThreshold }?.timestamp ?: building.lastTs
        val trimmedEnd = trimmedLastTs + CADENCE_SECONDS
        val effortSamples = building.samples.filter { it.timestamp <= trimmedLastTs }
        val heartRates = effortSamples.map { it.heartRate!! }
        DetectedSession(building.firstTs, trimmedEnd, heartRates.min(), heartRates.max())
    }
}

/**
 * True when [samples] (already sorted by timestamp, all at or above the floor threshold)
 * contain a run of at least [requiredMinutes] *consecutive* minutes -- adjacent timestamps
 * exactly [CADENCE_SECONDS] apart, no gap tolerated -- each at or above [enterThreshold].
 * Deliberately stricter than the floor-based dip tolerance the caller already applied when
 * building the block: this check exists to confirm genuine sustained effort actually
 * happened somewhere in the block, not to be forgiving about it.
 */
private fun hasSustainedEntry(samples: List<MinuteSample>, enterThreshold: Double, requiredMinutes: Int): Boolean {
    var run = 0
    var previousTimestamp: Long? = null
    for (sample in samples) {
        val contiguous = previousTimestamp != null && sample.timestamp - previousTimestamp == CADENCE_SECONDS
        val heartRate = sample.heartRate
        run = if (heartRate != null && heartRate >= enterThreshold) {
            if (contiguous) run + 1 else 1
        } else {
            0
        }
        if (run >= requiredMinutes) return true
        previousTimestamp = sample.timestamp
    }
    return false
}
