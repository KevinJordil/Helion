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
        // resting estimate and spread are both exactly predictable.
        val minutes = (0 until 20).flatMap { day ->
            (0 until 100).map { minute -> quietSample(day * 86_400L + minute * 60L, heartRate = 60) }
        }
        val baseline = computeHeartRateBaseline(minutes, zone, defaults)!!
        assertEquals(60.0, baseline.restingBpm, 0.01)
        // A perfectly uniform history has zero measured spread; the floor takes over.
        assertEquals(defaults.minSpreadBpm, baseline.spreadBpm, 0.01)
    }

    @Test
    fun `a brief non-exercise spike does not blow out the resting rate or the spread`() {
        // Same 2000 quiet minutes as above, plus a handful of elevated minutes on the last
        // day -- a stressful afternoon, not a workout. Both percentiles this baseline reads
        // (15th and 85th) fall comfortably inside the 2000-strong quiet block, so the spike
        // must not move either number.
        val quiet = (0 until 20).flatMap { day ->
            (0 until 100).map { minute -> quietSample(day * 86_400L + minute * 60L, heartRate = 60) }
        }
        val spike = (0 until 5).map { minute -> quietSample(19 * 86_400L + 50_000L + minute * 60L, heartRate = 150) }
        val baseline = computeHeartRateBaseline(quiet + spike, zone, defaults)!!

        assertEquals(60.0, baseline.restingBpm, 0.01)
        assertEquals(defaults.minSpreadBpm, baseline.spreadBpm, 0.01)
    }

    @Test
    fun `elevated threshold is the larger of the relative and the absolute margin above resting`() {
        val baseline = HeartRateBaseline(restingBpm = 60.0, spreadBpm = 5.0, distinctDays = 20)
        // 2.5 * 5 = 12.5, dwarfed by the 25 bpm absolute floor.
        assertEquals(85.0, baseline.elevatedThresholdBpm(defaults), 0.01)

        val wideSpreadBaseline = HeartRateBaseline(restingBpm = 60.0, spreadBpm = 40.0, distinctDays = 20)
        // 2.5 * 40 = 100, now dwarfing the 25 bpm floor.
        assertEquals(160.0, wideSpreadBaseline.elevatedThresholdBpm(defaults), 0.01)
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
