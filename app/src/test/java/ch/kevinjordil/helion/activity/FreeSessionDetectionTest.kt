package ch.kevinjordil.helion.activity

import ch.kevinjordil.helion.store.MinuteSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeSessionDetectionTest {

    private val thresholds = DetectionThresholds()

    // Resting 51, max 177 -- the owner's own real-export figures. range = 126;
    // enter = 51 + 0.55*126 = 120.3; floor = 51 + 0.32*126 = 91.32.
    private val baseline = HeartRateBaseline(restingBpm = 51.0, maxBpm = 177.0, distinctDays = 20)

    private fun burst(fromInclusive: Long, toInclusive: Long, heartRate: Int) =
        (fromInclusive..toInclusive step 60).map { ts ->
            MinuteSample(timestamp = ts, steps = 0, intensity = 40, rawKind = null, heartRate = heartRate, sleepStage = null)
        }

    @Test
    fun `a clean sustained block becomes one session`() {
        // 30 minutes at 150 bpm, clearly above both floor (91.32) and enter (120.3), and
        // comfortably past the 5-minute entry-sustain requirement.
        val minutes = burst(0, 1_740, heartRate = 150) // 30 minutes

        val sessions = detectFreeSessions(minutes, excludedRanges = emptyList(), baseline, thresholds)

        assertEquals(1, sessions.size)
        val session = sessions.single()
        assertEquals(0L, session.start)
        assertEquals(1_800L, session.end) // last elevated minute (1_740) + one minute
        assertEquals(150, session.minHeartRate)
        assertEquals(150, session.maxHeartRate)
    }

    @Test
    fun `a dip within the dip-tolerance window stays ONE session, like the tournament's between-match lulls`() {
        // A 25-minute burst at match intensity (well past the entry gate), a 17-minute dip
        // to a between-match heart rate that clears the floor (91.32) but not the enter
        // threshold, then another 25-minute burst -- exactly the shape measured against the
        // real tournament evening, where the longest below-floor dip between any two
        // matches was 17 minutes.
        val firstMatch = burst(0, 1_440, heartRate = 150) // 25 minutes
        val betweenMatches = burst(1_500, 2_520, heartRate = 95) // 17 minutes, above floor
        val secondMatch = burst(2_580, 4_020, heartRate = 150) // 25 minutes

        val sessions = detectFreeSessions(firstMatch + betweenMatches + secondMatch, excludedRanges = emptyList(), baseline, thresholds)

        assertEquals(1, sessions.size)
        val session = sessions.single()
        assertEquals(0L, session.start)
        assertEquals(4_080L, session.end) // last elevated minute (4_020) + one minute
    }

    @Test
    fun `a dip longer than the tolerance splits into two sessions`() {
        // Same shape as above, but the gap between the two bursts is a genuine dip below
        // the floor lasting longer than the 20-minute tolerance -- two separate outings,
        // not one long one.
        val first = burst(0, 1_440, heartRate = 150) // 25 minutes
        val second = burst(2_760, 4_200, heartRate = 150) // 25 minutes, 22-minute gap

        val sessions = detectFreeSessions(first + second, excludedRanges = emptyList(), baseline, thresholds)

        assertEquals(2, sessions.size)
        assertEquals(0L to 1_500L, sessions[0].start to sessions[0].end)
        assertEquals(2_760L to 4_260L, sessions[1].start to sessions[1].end)
    }

    @Test
    fun `a long stretch that only ever reaches the floor, never the enter threshold, is not a session`() {
        // 40 minutes at 100 bpm: above the floor (91.32) throughout, long enough to clear
        // minFreeSessionMinutes, but never once reaches the enter threshold (120.3) -- a
        // mildly elevated evening, not confirmed real effort. The entry gate exists exactly
        // for this shape.
        val minutes = burst(0, 2_340, heartRate = 100) // 40 minutes
        assertTrue(detectFreeSessions(minutes, excludedRanges = emptyList(), baseline, thresholds).isEmpty())
    }

    @Test
    fun `an existing activity sitting inside a would-be dip still forces a split`() {
        // Same shape as the merging test above, but this time the gap is not empty: some
        // other activity (already decided, or a candidate this same pass just created)
        // occupies part of it, so the two bursts must never be read as one continuous
        // session spanning across a range that is not actually free.
        val first = burst(0, 1_440, heartRate = 150) // 25 minutes
        val second = burst(2_040, 3_480, heartRate = 150) // 25 minutes, 10-minute gap -- would merge
        val excluded = listOf(1_450L until 1_460L)

        val sessions = detectFreeSessions(first + second, excludedRanges = excluded, baseline, thresholds)

        assertEquals(2, sessions.size)
    }

    @Test
    fun `minutes inside an excluded range are never considered at all`() {
        val minutes = burst(0, 1_740, heartRate = 150)
        val sessions = detectFreeSessions(minutes, excludedRanges = listOf(0L until 2_000L), baseline, thresholds)
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `a session shorter than the minimum sustained duration is dropped even if the entry gate clears`() {
        val minutes = burst(0, 480, heartRate = 150) // 9 minutes, short of the 20-minute floor
        assertTrue(detectFreeSessions(minutes, excludedRanges = emptyList(), baseline, thresholds).isEmpty())
    }

    @Test
    fun `a quiet day with no elevated minute at all produces nothing`() {
        val minutes = burst(0, 10_000, heartRate = 55)
        assertTrue(detectFreeSessions(minutes, excludedRanges = emptyList(), baseline, thresholds).isEmpty())
    }
}
