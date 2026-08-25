package ch.kevinjordil.helion.ui.metric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChartScrubTest {

    @Test
    fun `an empty list has nothing to scrub to`() {
        assertNull(scrubReading(emptyList(), 0.5f))
    }

    @Test
    fun `a single reading is returned regardless of drag position`() {
        val only = Reading(timestamp = 1_000, value = 42.0)
        assertEquals(only, scrubReading(listOf(only), 0f))
        assertEquals(only, scrubReading(listOf(only), 1f))
        assertEquals(only, scrubReading(listOf(only), 0.37f))
    }

    @Test
    fun `the left edge resolves to the oldest reading`() {
        val readings = listOf(
            Reading(timestamp = 0, value = 1.0),
            Reading(timestamp = 100, value = 2.0),
            Reading(timestamp = 200, value = 3.0),
        )
        assertEquals(readings.first(), scrubReading(readings, 0f))
    }

    @Test
    fun `the right edge resolves to the newest reading`() {
        val readings = listOf(
            Reading(timestamp = 0, value = 1.0),
            Reading(timestamp = 100, value = 2.0),
            Reading(timestamp = 200, value = 3.0),
        )
        assertEquals(readings.last(), scrubReading(readings, 1f))
    }

    @Test
    fun `the midpoint resolves to the nearest reading in time, not the nearest index`() {
        val readings = listOf(
            Reading(timestamp = 0, value = 1.0),
            Reading(timestamp = 10, value = 2.0),
            Reading(timestamp = 200, value = 3.0),
        )
        // Midpoint of the [0, 200] span is timestamp 100, closer to the reading at 10 than
        // to the one at 200, even though index-wise 200 is the "middle-ish" entry.
        assertEquals(readings[1], scrubReading(readings, 0.5f))
    }

    @Test
    fun `out-of-range fractions are clamped rather than crashing`() {
        val readings = listOf(
            Reading(timestamp = 0, value = 1.0),
            Reading(timestamp = 100, value = 2.0),
        )
        assertEquals(readings.first(), scrubReading(readings, -5f))
        assertEquals(readings.last(), scrubReading(readings, 5f))
    }

    @Test
    fun `identical timestamps -- a zero-width span -- still move through the list by position`() {
        val readings = listOf(
            Reading(timestamp = 500, value = 1.0),
            Reading(timestamp = 500, value = 2.0),
            Reading(timestamp = 500, value = 3.0),
        )
        assertEquals(readings.first(), scrubReading(readings, 0f))
        assertEquals(readings.last(), scrubReading(readings, 1f))
    }
}
