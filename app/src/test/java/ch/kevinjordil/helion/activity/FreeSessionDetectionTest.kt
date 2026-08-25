package ch.kevinjordil.helion.activity

import ch.kevinjordil.helion.store.MinuteSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeSessionDetectionTest {

    private val thresholds = DetectionThresholds()

    // Resting 60, spread 5 -> elevated threshold is 60 + max(25, 2.5*5) = 85 bpm.
    private val baseline = HeartRateBaseline(restingBpm = 60.0, spreadBpm = 5.0, distinctDays = 20)

    private fun burst(fromInclusive: Long, toInclusive: Long, heartRate: Int) =
        (fromInclusive..toInclusive step 60).map { ts ->
            MinuteSample(timestamp = ts, steps = 0, intensity = 40, rawKind = null, heartRate = heartRate, sleepStage = null)
        }

    @Test
    fun `a genuine gap between two elevated bursts splits into two sessions`() {
        val first = burst(0, 1_440, heartRate = 140) // 25 minutes
        val second = burst(1_800, 3_240, heartRate = 140) // 25 minutes, 360s (6 min) later

        val sessions = detectFreeSessions(first + second, excludedRanges = emptyList(), baseline, thresholds)

        assertEquals(2, sessions.size)
        assertEquals(0L to 1_500L, sessions[0].start to sessions[0].end)
        assertEquals(1_800L to 3_300L, sessions[1].start to sessions[1].end)
    }

    @Test
    fun `a dip within the dip-tolerance window stays ONE session, not shredded into fragments`() {
        // Two 11-minute bursts (typical of alternating rallies and picking up shuttlecocks)
        // separated by exactly the tolerated dip: 240s (4 minutes), the tolerance boundary.
        val first = burst(0, 600, heartRate = 140)
        val second = burst(840, 1_440, heartRate = 140)

        val sessions = detectFreeSessions(first + second, excludedRanges = emptyList(), baseline, thresholds)

        assertEquals(1, sessions.size)
        assertEquals(0L, sessions.single().start)
        assertEquals(1_500L, sessions.single().end) // last elevated minute (1_440) + one minute
    }

    @Test
    fun `an existing activity sitting inside a would-be dip still forces a split`() {
        // Same 240s gap as the merging test above, but this time it is not empty: some
        // other activity (already decided, or a candidate this same pass just created)
        // occupies part of it, so the two bursts must never be read as one continuous
        // session spanning across a range that is not actually free.
        val first = burst(0, 1_440, heartRate = 140) // 25 minutes
        val second = burst(1_800, 3_240, heartRate = 140) // 25 minutes
        val excluded = listOf(1_450L until 1_460L)

        // Shrink the gap to something that WOULD merge if not for the excluded range: move
        // the second burst closer, 240s after the first ends.
        val closeSecond = burst(1_680, 3_120, heartRate = 140)
        val sessions = detectFreeSessions(first + closeSecond, excludedRanges = excluded, baseline, thresholds)

        assertEquals(2, sessions.size)
    }

    @Test
    fun `minutes inside an excluded range are never considered at all`() {
        val minutes = burst(0, 1_440, heartRate = 140)
        val sessions = detectFreeSessions(minutes, excludedRanges = listOf(0L until 2_000L), baseline, thresholds)
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `a session shorter than the minimum sustained duration is dropped`() {
        val minutes = burst(0, 600, heartRate = 140) // 11 minutes, short of the 20-minute floor
        assertTrue(detectFreeSessions(minutes, excludedRanges = emptyList(), baseline, thresholds).isEmpty())
    }

    @Test
    fun `no elevated minute at all produces nothing`() {
        val minutes = burst(0, 10_000, heartRate = 60)
        assertTrue(detectFreeSessions(minutes, excludedRanges = emptyList(), baseline, thresholds).isEmpty())
    }
}
