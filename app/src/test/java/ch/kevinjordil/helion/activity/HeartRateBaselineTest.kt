package ch.kevinjordil.helion.activity

import ch.kevinjordil.helion.store.MinuteSample
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** UTC throughout: distinct-day counting must not depend on the machine running the test. */
class HeartRateBaselineTest {

    private val zone = ZoneOffset.UTC
    private val defaults = DetectionThresholds()

    private fun quietSample(timestamp: Long, heartRate: Int = 60) =
        MinuteSample(timestamp = timestamp, steps = 0, intensity = 0, rawKind = null, heartRate = heartRate, sleepStage = null)

    @Test
    fun `no heart-rate data at all yields no baseline`() {
        assertNull(computeHeartRateBaseline(emptyList(), zone, defaults))
    }

    @Test
    fun `fewer distinct days than required yields no baseline`() {
        val minutes = (0 until defaults.minBaselineDays - 1).map { day -> quietSample(day * 86_400L) }
        assertNull(computeHeartRateBaseline(minutes, zone, defaults))
    }

    @Test
    fun `exactly the minimum distinct days yields a baseline`() {
        val minutes = (0 until defaults.minBaselineDays).map { day -> quietSample(day * 86_400L) }
        val baseline = computeHeartRateBaseline(minutes, zone, defaults)
        assertEquals(defaults.minBaselineDays, baseline?.distinctDays)
    }

    @Test
    fun `resting rate is a low percentile of ordinary readings, not the bare minimum`() {
        // 20 days, 100 quiet minutes each, all at 60 bpm -- a uniform history so the
        // resting estimate and the observed max are both exactly predictable.
        val minutes = (0 until 20).flatMap { day ->
            (0 until 100).map { minute -> quietSample(day * 86_400L + minute * 60L, heartRate = 60) }
        }
        val baseline = computeHeartRateBaseline(minutes, zone, defaults)!!
        assertEquals(60.0, baseline.restingBpm, 0.01)
        assertEquals(60.0, baseline.maxBpm, 0.01)
    }

    @Test
    fun `a brief high effort spike does not move the resting estimate but does become the new max`() {
        // Same 2000 quiet minutes as above, plus a handful of elevated minutes on the last
        // day -- a real short burst of effort, not a workout long enough to matter for
        // resting. The 25th-percentile resting estimate sits comfortably inside the
        // 2000-strong quiet block and must not move; the observed max, deliberately not a
        // percentile (see HeartRateBaseline's own kdoc), must move to the spike's own peak
        // -- that reactivity is exactly what lets a genuine short burst of real exertion
        // raise the ceiling the two detection thresholds are scaled against.
        val quiet = (0 until 20).flatMap { day ->
            (0 until 100).map { minute -> quietSample(day * 86_400L + minute * 60L, heartRate = 60) }
        }
        val spike = (0 until 5).map { minute -> quietSample(19 * 86_400L + 50_000L + minute * 60L, heartRate = 150) }
        val baseline = computeHeartRateBaseline(quiet + spike, zone, defaults)!!

        assertEquals(60.0, baseline.restingBpm, 0.01)
        assertEquals(150.0, baseline.maxBpm, 0.01)
    }

    @Test
    fun `enter and floor thresholds split the observed range by their own fractions, floored by minRangeBpm`() {
        // resting 51, max 177 -- the owner's own real-export figures this module was
        // calibrated against. range = 126; enter = 51 + 0.55*126 = 120.3; floor = 51 + 0.32*126 = 91.32.
        val baseline = HeartRateBaseline(restingBpm = 51.0, maxBpm = 177.0, distinctDays = 20)
        assertEquals(120.3, baseline.enterThresholdBpm(defaults), 0.01)
        assertEquals(91.32, baseline.floorThresholdBpm(defaults), 0.01)

        // A narrow observed range (no real effort on record yet) is floored at minRangeBpm
        // (40) rather than collapsing both thresholds down near resting.
        val flatBaseline = HeartRateBaseline(restingBpm = 60.0, maxBpm = 65.0, distinctDays = 20)
        assertEquals(60.0 + 0.55 * 40.0, flatBaseline.enterThresholdBpm(defaults), 0.01)
        assertEquals(60.0 + 0.32 * 40.0, flatBaseline.floorThresholdBpm(defaults), 0.01)
    }

    @Test
    fun `missing heart rate readings are ignored rather than treated as zero`() {
        val minutes = (0 until 20).flatMap { day ->
            (0 until 100).map { minute ->
                MinuteSample(day * 86_400L + minute * 60L, steps = 0, intensity = 0, rawKind = null, heartRate = null, sleepStage = null)
            }
        } + quietSample(20 * 86_400L)
        // Only one day actually carries a heart-rate reading.
        assertTrue(computeHeartRateBaseline(minutes, zone, defaults) == null)
    }
}
