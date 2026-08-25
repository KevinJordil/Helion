package ch.kevinjordil.helion.ui.ribbon

import ch.kevinjordil.helion.ui.metric.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RibbonGeometryTest {

    @Test
    fun `no readings produces no bars -- an empty ribbon reads as one continuous gap`() {
        val bars = buildRibbon(emptyList(), windowStart = 0, windowEnd = 86_400)
        assertTrue(bars.isEmpty())
    }

    @Test
    fun `a non-positive window produces no bars regardless of readings`() {
        val readings = listOf(Reading(timestamp = 100, value = 10.0))
        assertTrue(buildRibbon(readings, windowStart = 100, windowEnd = 100).isEmpty())
        assertTrue(buildRibbon(readings, windowStart = 200, windowEnd = 100).isEmpty())
    }

    @Test
    fun `readings outside the window are excluded from every bucket`() {
        val readings = listOf(
            Reading(timestamp = -10, value = 999.0), // before the window
            Reading(timestamp = 90_000, value = 999.0), // after the window
            Reading(timestamp = 100, value = 5.0),
        )
        val bars = buildRibbon(readings, windowStart = 0, windowEnd = 86_400, bucketCount = 24)
        assertEquals(1, bars.size)
    }

    @Test
    fun `a single occupied bucket sits at mid-height rather than dividing by a zero value-range`() {
        val readings = listOf(Reading(timestamp = 100, value = 42.0))
        val bars = buildRibbon(readings, windowStart = 0, windowEnd = 86_400, bucketCount = 24)
        assertEquals(1, bars.size)
        assertEquals(0.5f, bars.single().valueFraction, 0f)
    }

    @Test
    fun `identical values across every occupied bucket all sit at mid-height`() {
        val readings = listOf(
            Reading(timestamp = 100, value = 7.0),
            Reading(timestamp = 50_000, value = 7.0),
        )
        val bars = buildRibbon(readings, windowStart = 0, windowEnd = 86_400, bucketCount = 24)
        assertEquals(2, bars.size)
        bars.forEach { assertEquals(0.5f, it.valueFraction, 0f) }
    }

    @Test
    fun `the min and max buckets normalise to the extremes`() {
        val readings = listOf(
            Reading(timestamp = 100, value = 0.0),
            Reading(timestamp = 50_000, value = 100.0),
        )
        val bars = buildRibbon(readings, windowStart = 0, windowEnd = 86_400, bucketCount = 24).sortedBy { it.xFraction }
        assertEquals(0f, bars.first().valueFraction, 0f)
        assertEquals(1f, bars.last().valueFraction, 0f)
    }

    @Test
    fun `multiple readings in the same bucket are averaged`() {
        val readings = listOf(
            Reading(timestamp = 100, value = 10.0),
            Reading(timestamp = 200, value = 20.0),
            Reading(timestamp = 50_000, value = 100.0),
        )
        val bars = buildRibbon(readings, windowStart = 0, windowEnd = 86_400, bucketCount = 24).sortedBy { it.xFraction }
        // The two early readings share a bucket and average to 15, the minimum; the lone
        // late reading is the maximum.
        assertEquals(0f, bars.first().valueFraction, 0f)
        assertEquals(1f, bars.last().valueFraction, 0f)
    }

    @Test
    fun `a bucket's x fraction sits at its centre, sorted by time`() {
        val readings = listOf(
            Reading(timestamp = 0, value = 1.0), // bucket 0 of 2, spans [0, 43_200)
            Reading(timestamp = 43_200, value = 2.0), // bucket 1 of 2, spans [43_200, 86_400)
        )
        val bars = buildRibbon(readings, windowStart = 0, windowEnd = 86_400, bucketCount = 2)
        assertEquals(2, bars.size)
        assertEquals(0.25f, bars[0].xFraction, 0f)
        assertEquals(0.75f, bars[1].xFraction, 0f)
    }

    @Test
    fun `PAI-like sparse readings across a day still produce a handful of separated bars`() {
        // Roughly every 8 hours: three readings across a 24h window.
        val readings = listOf(
            Reading(timestamp = 0, value = 10.0),
            Reading(timestamp = 8 * 3_600L, value = 20.0),
            Reading(timestamp = 16 * 3_600L, value = 30.0),
        )
        val bars = buildRibbon(readings, windowStart = 0, windowEnd = 86_400, bucketCount = 48)
        assertEquals(3, bars.size)
    }
}
