package ch.kevinjordil.helion.activity

import ch.kevinjordil.helion.store.MinuteSample

/** One minute, in seconds -- this device's own cadence. */
private const val CADENCE_SECONDS = 60L

/**
 * The real effort window pass 1 found overlapping a slot's declared occurrence. [minHeartRate]
 * and [maxHeartRate] are across the elevated minutes that produced this session, not the
 * declared range -- exactly the evidence the owner can check the proposal against (see
 * [ActivityDetector]'s note text).
 */
data class TrimmedEffort(val start: Long, val end: Long, val minHeartRate: Int, val maxHeartRate: Int)

/**
 * Pass 1: a slot occurrence anchors WHERE to look for a session and WHAT to call it (its
 * sport and title come from the slot) -- it no longer decides WHEN the session starts or
 * ends. The real boundaries are whatever contiguous elevated heart-rate block overlaps the
 * declared occurrence, located with the same floor-threshold, dip-tolerant merge
 * [detectFreeSessions] (pass 2) uses -- see [DetectionThresholds.dipToleranceMinutes] -- even
 * when that block starts earlier or runs later than what was declared. A course announced
 * 20:00-22:00 that he reached at 20:10 and left at 21:45 still yields 20:10-21:45; one he
 * arrived at 19:50 for or played past 22:10 for now yields 19:50-... or ...-22:10 instead of
 * being clipped to the declared hour.
 *
 * The search is bounded, not open-ended: only minutes within
 * `[occurrence.start - margin, occurrence.end + margin)` are looked at, where margin is
 * [DetectionThresholds.slotExtensionMarginMinutes] -- see that property's own kdoc for why a
 * hard stop is necessary even though pass 2's dip tolerance is itself already forgiving.
 *
 * [minutes] is expected to already cover (at least) that widened window; anything outside it
 * is ignored, so a caller may pass in a wider read without needing to pre-trim it itself.
 *
 * Boundaries are the first and last floor-crossing minute (see
 * [HeartRateBaseline.floorThresholdBpm]) of whichever merged block overlaps the *declared*
 * occurrence -- a slot occurrence is already a declared commitment (the owner said he would
 * be there), so, exactly as before, this pass does not need pass 2's additional "did real
 * effort ever start" entry confirmation before trusting an elevated stretch at all.
 *
 * Returns null -- create nothing -- in two cases:
 * - No block overlapping the declared occurrence clears the floor threshold anywhere: the
 *   window is flat, he was not there. An elevation entirely outside the declared window (even
 *   if within the margin) does not count -- that is a different, unrelated event.
 * - The overlapping block's total span is shorter than [DetectionThresholds.minSlotEffortMinutes]:
 *   a brief blip inside an otherwise unattended slot, not a real session.
 */
fun trimSlotOccurrence(
    occurrence: SlotOccurrence,
    minutes: List<MinuteSample>,
    baseline: HeartRateBaseline,
    thresholds: DetectionThresholds = DetectionThresholds(),
): TrimmedEffort? {
    val floorThreshold = baseline.floorThresholdBpm(thresholds)
    val marginSeconds = thresholds.slotExtensionMarginMinutes * CADENCE_SECONDS
    val windowStart = occurrence.start - marginSeconds
    val windowEnd = occurrence.end + marginSeconds
    val dipToleranceSeconds = thresholds.dipToleranceMinutes * CADENCE_SECONDS

    val aboveFloor = minutes
        .filter {
            it.timestamp >= windowStart && it.timestamp < windowEnd && it.heartRate != null && it.heartRate >= floorThreshold
        }
        .sortedBy { it.timestamp }
    if (aboveFloor.isEmpty()) return null

    data class Building(var firstTs: Long, var lastTs: Long, val heartRates: MutableList<Int> = mutableListOf())

    // Merge into blocks, tolerating a gap (a genuine dip below floor, or missing minutes) of
    // at most dipToleranceMinutes -- the same rule pass 2 uses to hold one session together
    // across a between-rally lull, rather than fragmenting it.
    val blocks = mutableListOf<Building>()
    var current: Building? = null
    for (sample in aboveFloor) {
        val heartRate = sample.heartRate!!
        val building = current
        if (building == null || sample.timestamp - building.lastTs > dipToleranceSeconds) {
            val fresh = Building(sample.timestamp, sample.timestamp).also { it.heartRates.add(heartRate) }
            current = fresh
            blocks.add(fresh)
        } else {
            building.lastTs = sample.timestamp
            building.heartRates.add(heartRate)
        }
    }

    // Only a block that overlaps the declared window at all is this occurrence's session --
    // a block that lives entirely under the margin with no reach into the declared window is
    // a different event the owner never committed to, and must not be attributed to this slot.
    val block = blocks.firstOrNull { it.firstTs < occurrence.end && it.lastTs + CADENCE_SECONDS > occurrence.start }
        ?: return null

    val start = block.firstTs
    val end = block.lastTs + CADENCE_SECONDS
    val durationMinutes = (end - start) / CADENCE_SECONDS
    if (durationMinutes < thresholds.minSlotEffortMinutes) return null

    return TrimmedEffort(start = start, end = end, minHeartRate = block.heartRates.min(), maxHeartRate = block.heartRates.max())
}
