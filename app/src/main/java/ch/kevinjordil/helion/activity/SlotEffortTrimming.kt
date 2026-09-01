package ch.kevinjordil.helion.activity

import ch.kevinjordil.helion.store.MinuteSample

/** One minute, in seconds -- this device's own cadence. */
private const val CADENCE_SECONDS = 60L

/**
 * The real effort window pass 1 found inside a slot's declared occurrence. [minHeartRate]
 * and [maxHeartRate] are across the elevated minutes that produced this trim, not the
 * whole declared range -- exactly the evidence the owner can check the proposal against
 * (see [ActivityDetector]'s note text).
 */
data class TrimmedEffort(val start: Long, val end: Long, val minHeartRate: Int, val maxHeartRate: Int)

/**
 * Pass 1: trims [occurrence]'s declared range down to where heart rate actually rose,
 * rather than trusting the declared boundaries themselves. A course announced 20:00-22:00
 * that he reached at 20:10 and left at 21:45 must yield 20:10-21:45, not the full two
 * hours.
 *
 * [minutes] is expected to already cover (at least) `[occurrence.start, occurrence.end)`;
 * anything outside that range is ignored, so a caller may pass in a slightly wider read
 * without needing to pre-trim it itself.
 *
 * The boundaries are the first and last minute whose heart rate clears
 * [HeartRateBaseline.floorThresholdBpm] -- the *stay-in* half of the hysteresis pair, not
 * the higher *enter* threshold: a slot occurrence is already a declared commitment (the
 * owner said he would be there), so pass 1 does not need the same "did real effort ever
 * start" confirmation pass 2 needs before trusting an elevated stretch at all. Everything
 * between the first and last floor-crossing minute is kept as one continuous span
 * regardless of any dip inside it -- [detectFreeSessions] (pass 2) is where dip *tolerance*
 * as a splitting rule lives; here there is only one occurrence to decide about, so its
 * extremes are enough.
 *
 * Returns null -- create nothing -- in two cases:
 * - No minute in range clears the threshold at all: the window is flat, he was not there.
 * - The elevated span is shorter than [DetectionThresholds.minSlotEffortMinutes]: a brief
 *   blip inside an otherwise unattended slot, not a real session.
 */
fun trimSlotOccurrence(
    occurrence: SlotOccurrence,
    minutes: List<MinuteSample>,
    baseline: HeartRateBaseline,
    thresholds: DetectionThresholds = DetectionThresholds(),
): TrimmedEffort? {
    val threshold = baseline.floorThresholdBpm(thresholds)
    val elevated = minutes.filter {
        it.timestamp >= occurrence.start && it.timestamp < occurrence.end && it.heartRate != null && it.heartRate >= threshold
    }
    if (elevated.isEmpty()) return null

    val start = elevated.minOf { it.timestamp }
    val lastMinuteStart = elevated.maxOf { it.timestamp }
    val end = lastMinuteStart + CADENCE_SECONDS
    val durationMinutes = (end - start) / CADENCE_SECONDS
    if (durationMinutes < thresholds.minSlotEffortMinutes) return null

    val heartRates = elevated.map { it.heartRate!! }
    return TrimmedEffort(start = start, end = end, minHeartRate = heartRates.min(), maxHeartRate = heartRates.max())
}
