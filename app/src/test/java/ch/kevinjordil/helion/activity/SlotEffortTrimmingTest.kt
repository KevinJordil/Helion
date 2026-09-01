package ch.kevinjordil.helion.activity

import ch.kevinjordil.helion.store.MinuteSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlotEffortTrimmingTest {

    private val thresholds = DetectionThresholds()

    // Resting 60, max 140 -> range 80 (above the 40 bpm floor) -> floor threshold is
    // 60 + 0.32*80 = 85.6 bpm.
    private val baseline = HeartRateBaseline(restingBpm = 60.0, maxBpm = 140.0, distinctDays = 20)

    private fun sample(timestamp: Long, heartRate: Int) =
        MinuteSample(timestamp = timestamp, steps = 0, intensity = 20, rawKind = null, heartRate = heartRate, sleepStage = null)

    private fun minuteRange(fromInclusive: Long, toInclusive: Long, heartRate: Int) =
        (fromInclusive..toInclusive step 60).map { sample(it, heartRate) }

    @Test
    fun `trims a declared range down to where heart rate actually rose`() {
        // Declared 20:00-22:00 (72_000..79_200); reached at 20:10 (72_600), left at 21:45 (78_300).
        val occurrence = SlotOccurrence(start = 72_000, end = 79_200)
        val minutes = minuteRange(72_000, 72_540, heartRate = 60) +
            minuteRange(72_600, 78_300, heartRate = 140) +
            minuteRange(78_360, 79_140, heartRate = 60)

        val trimmed = trimSlotOccurrence(occurrence, minutes, baseline, thresholds)!!

        assertEquals(72_600L, trimmed.start)
        assertEquals(78_360L, trimmed.end) // last elevated minute (78_300) + one minute
        assertEquals(140, trimmed.minHeartRate)
        assertEquals(140, trimmed.maxHeartRate)
    }

    @Test
    fun `a flat window -- no elevated minute at all -- produces nothing`() {
        val occurrence = SlotOccurrence(start = 72_000, end = 79_200)
        val minutes = minuteRange(72_000, 79_140, heartRate = 60)

        assertNull(trimSlotOccurrence(occurrence, minutes, baseline, thresholds))
    }

    @Test
    fun `a brief elevated blip shorter than the minimum effort duration produces nothing`() {
        val occurrence = SlotOccurrence(start = 72_000, end = 79_200)
        // A single elevated minute -- one bpm spike, not a session.
        val minutes = minuteRange(72_000, 72_540, heartRate = 60) +
            sample(75_000, heartRate = 140) +
            minuteRange(75_060, 79_140, heartRate = 60)

        assertNull(trimSlotOccurrence(occurrence, minutes, baseline, thresholds))
    }

    @Test
    fun `minutes outside the declared range are ignored`() {
        val occurrence = SlotOccurrence(start = 72_000, end = 79_200)
        // Elevated well before and well after the declared window only.
        val minutes = minuteRange(60_000, 71_940, heartRate = 140) +
            minuteRange(72_000, 79_140, heartRate = 60) +
            minuteRange(79_200, 90_000, heartRate = 140)

        assertNull(trimSlotOccurrence(occurrence, minutes, baseline, thresholds))
    }
}
