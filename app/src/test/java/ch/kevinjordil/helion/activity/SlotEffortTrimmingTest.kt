package ch.kevinjordil.helion.activity

import ch.kevinjordil.helion.store.MinuteSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlotEffortTrimmingTest {

    private val thresholds = DetectionThresholds()
    private val marginSeconds = thresholds.slotExtensionMarginMinutes * 60L

    // Resting 60, max 140 -> range 80 (above the 40 bpm floor) -> floor threshold is
    // 60 + 0.32*80 = 85.6 bpm.
    private val baseline = HeartRateBaseline(restingBpm = 60.0, maxBpm = 140.0, distinctDays = 20)

    private fun sample(timestamp: Long, heartRate: Int) =
        MinuteSample(timestamp = timestamp, steps = 0, intensity = 20, rawKind = null, heartRate = heartRate, sleepStage = null)

    private fun minuteRange(fromInclusive: Long, toInclusive: Long, heartRate: Int) =
        (fromInclusive..toInclusive step 60).map { sample(it, heartRate) }

    @Test
    fun `a session entirely inside the declared window is trimmed to it, unchanged`() {
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
    fun `a session that keeps going past the declared end follows it there`() {
        // Declared 20:00-21:00 (72_000..75_600); real effort keeps going until 21:20 (76_800).
        val occurrence = SlotOccurrence(start = 72_000, end = 75_600)
        val minutes = minuteRange(72_000, 72_540, heartRate = 60) +
            minuteRange(72_600, 76_740, heartRate = 140) +
            minuteRange(76_800, 77_400, heartRate = 60)

        val trimmed = trimSlotOccurrence(occurrence, minutes, baseline, thresholds)!!

        assertEquals(72_600L, trimmed.start)
        assertEquals(76_800L, trimmed.end) // last elevated minute (76_740) + one minute -- past the declared 75_600 end
    }

    @Test
    fun `a session that started before the declared start follows it there`() {
        // Declared 20:10-21:00 (72_600..75_600); real effort already began at 19:50 (71_400).
        val occurrence = SlotOccurrence(start = 72_600, end = 75_600)
        val minutes = minuteRange(71_100, 71_340, heartRate = 60) +
            minuteRange(71_400, 75_300, heartRate = 140) +
            minuteRange(75_360, 75_900, heartRate = 60)

        val trimmed = trimSlotOccurrence(occurrence, minutes, baseline, thresholds)!!

        assertEquals(71_400L, trimmed.start) // earlier than the declared 72_600 start
        assertEquals(75_360L, trimmed.end)
    }

    @Test
    fun `extension stops at the margin rather than following the session away`() {
        // Declared 20:00-21:00 (72_000..75_600). Elevated heart rate keeps going far past
        // any reasonable session length, well beyond the margin around the declared window.
        val occurrence = SlotOccurrence(start = 72_000, end = 75_600)
        val minutes = minuteRange(72_000, 72_540, heartRate = 60) +
            minuteRange(72_600, 90_000, heartRate = 140)

        val trimmed = trimSlotOccurrence(occurrence, minutes, baseline, thresholds)!!

        assertEquals(72_600L, trimmed.start)
        assertEquals(occurrence.end + marginSeconds, trimmed.end) // clipped at the margin, not the far-off last elevated minute
    }

    @Test
    fun `a flat window -- no elevated minute at all -- produces nothing`() {
        val occurrence = SlotOccurrence(start = 72_000, end = 79_200)
        val minutes = minuteRange(72_000, 79_140, heartRate = 60)

        assertNull(trimSlotOccurrence(occurrence, minutes, baseline, thresholds))
    }

    @Test
    fun `an elevation entirely under the margin, never touching the declared window, produces nothing`() {
        // Declared 20:00-21:00; the only elevated stretch is well before the declared start,
        // inside the margin but never overlapping the occurrence itself -- a different event.
        val occurrence = SlotOccurrence(start = 72_000, end = 75_600)
        val elevatedStart = occurrence.start - marginSeconds + 600
        val elevatedEnd = occurrence.start - 600
        val minutes = minuteRange(elevatedStart, elevatedEnd, heartRate = 140)

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
}
