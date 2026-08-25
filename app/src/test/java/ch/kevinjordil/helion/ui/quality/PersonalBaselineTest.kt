package ch.kevinjordil.helion.ui.quality

import ch.kevinjordil.helion.ui.metric.Reading
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val ZONE = ZoneId.of("Europe/Zurich")
private const val DAY = 86_400L

class PersonalBaselineTest {

    private fun readingsAcrossDays(days: Int, valueForDay: (Int) -> Double): List<Reading> =
        (0 until days).map { day -> Reading(timestamp = day * DAY + 43_200, value = valueForDay(day)) }

    @Test
    fun `fewer than the minimum distinct days yields no baseline`() {
        val readings = readingsAcrossDays(MIN_BASELINE_DAYS - 1) { 50.0 }
        assertNull(computeBaseline(readings, ZONE))
    }

    @Test
    fun `exactly the minimum distinct days is enough`() {
        val readings = readingsAcrossDays(MIN_BASELINE_DAYS) { 50.0 }
        assertEquals(MIN_BASELINE_DAYS, computeBaseline(readings, ZONE)?.distinctDays)
    }

    @Test
    fun `many readings within a single day do not count as history`() {
        // Heart rate reports every minute: a single busy hour must not look like "enough
        // personal history" just because the sample count is high.
        val readings = (0 until 120).map { minute -> Reading(timestamp = minute * 60L, value = 60.0) }
        assertNull(computeBaseline(readings, ZONE))
    }

    @Test
    fun `an empty history yields no baseline`() {
        assertNull(computeBaseline(emptyList(), ZONE))
    }

    @Test
    fun `a single outlier does not drag the median or the spread`() {
        // Six normal days around 50, one wild outlier day at 500.
        val values = listOf(48.0, 49.0, 50.0, 51.0, 52.0, 50.0, 500.0)
        val readings = values.mapIndexed { day, v -> Reading(timestamp = day * DAY + 43_200, value = v) }
        val baseline = computeBaseline(readings, ZONE)
        assertEquals(50.0, baseline!!.median, 0.001)
        // The robust spread must stay small, not be inflated by the single 500 outlier the
        // way a mean/stddev would be.
        assertTrue("spread should stay tight despite the outlier, was ${baseline.spread}", baseline.spread < 10.0)
    }

    @Test
    fun `insufficient history always yields InsufficientHistory regardless of the current value`() {
        assertEquals(PersonalBaseline.InsufficientHistory, placeAgainstBaseline(999.0, null))
    }

    @Test
    fun `a value within the usual band is placed as USUAL and not notable`() {
        val readings = readingsAcrossDays(10) { day -> 48.0 + (day % 3) } // small natural spread around ~49
        val baseline = computeBaseline(readings, ZONE)!!
        val result = placeAgainstBaseline(baseline.median, baseline) as PersonalBaseline.Placed
        assertEquals(Position.USUAL, result.position)
        assertEquals(false, result.isNotable)
    }

    @Test
    fun `a moderately low value is BELOW but not necessarily notable`() {
        val varied = readingsAcrossDays(10) { day -> 95.0 + (day % 5) } // values 95..99, real spread
        val baseline = computeBaseline(varied, ZONE)!!
        val slightlyBelow = baseline.median - baseline.spread * 1.5
        val result = placeAgainstBaseline(slightlyBelow, baseline)
        assertTrue(result is PersonalBaseline.Placed)
        assertEquals(Position.BELOW, (result as PersonalBaseline.Placed).position)
        assertEquals(false, result.isNotable)
    }

    @Test
    fun `a value far above the baseline is ABOVE and notable`() {
        val readings = readingsAcrossDays(10) { day -> 50.0 + (day % 3) }
        val baseline = computeBaseline(readings, ZONE)!!
        val farAbove = baseline.median + baseline.spread * 10
        val result = placeAgainstBaseline(farAbove, baseline) as PersonalBaseline.Placed
        assertEquals(Position.ABOVE, result.position)
        assertEquals(true, result.isNotable)
    }

    @Test
    fun `a zero-spread baseline -- every recent reading identical -- flags any real difference as notable`() {
        val readings = readingsAcrossDays(10) { 70.0 }
        val baseline = computeBaseline(readings, ZONE)!!
        assertEquals(0.0, baseline.spread, 0.0)

        val exact = placeAgainstBaseline(70.0, baseline) as PersonalBaseline.Placed
        assertEquals(Position.USUAL, exact.position)
        assertEquals(false, exact.isNotable)

        val different = placeAgainstBaseline(71.0, baseline) as PersonalBaseline.Placed
        assertEquals(Position.ABOVE, different.position)
        assertEquals(true, different.isNotable)
    }
}
