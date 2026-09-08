package ch.kevinjordil.helion.ui.metric

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // -- chartGridlines --------------------------------------------------------------

    @Test
    fun `gridlines land on round values within the range`() {
        // PAI's own real range: the reference threshold of 100 should be one of them.
        val lines = chartGridlines(0f, 219.3f)
        assertTrue("expected 100 among $lines", lines.contains(100f))
        lines.forEach { assertTrue("$it out of [0, 219.3]", it in 0f..219.3f) }
    }

    @Test
    fun `gridline count stays sensible, not one per pixel`() {
        val lines = chartGridlines(0f, 1000f, targetCount = 4)
        assertTrue("expected a handful of lines, got $lines", lines.size in 1..8)
    }

    @Test
    fun `an all-identical range produces a single gridline, not a division by zero`() {
        val lines = chartGridlines(42f, 42f)
        assertEquals(listOf(42f), lines)
    }

    @Test
    fun `a backwards or NaN range produces no gridlines`() {
        assertEquals(emptyList<Float>(), chartGridlines(10f, 5f))
        assertEquals(emptyList<Float>(), chartGridlines(Float.NaN, 5f))
        assertEquals(emptyList<Float>(), chartGridlines(0f, Float.NaN))
    }

    @Test
    fun `a narrow range still produces bounded, non-absurd ticks`() {
        val lines = chartGridlines(0.001f, 0.002f)
        assertTrue("expected a small number of ticks, got $lines", lines.size in 1..6)
        lines.forEach { assertTrue("$it out of range", it in 0f..0.003f) }
    }

    // -- chartTimeMarkers --------------------------------------------------------------

    private val utc = ZoneOffset.UTC

    private fun epoch(iso: String): Long = Instant.parse(iso).epochSecond

    @Test
    fun `a day-long span marks hours, not one per minute`() {
        val start = epoch("2026-01-01T00:00:00Z")
        val end = epoch("2026-01-02T00:00:00Z")
        val marks = chartTimeMarkers(start, end, utc)
        assertTrue("expected a handful of hour marks, got ${marks.size}", marks.size in 3..8)
        marks.forEach { assertTrue(it in start..end) }
    }

    @Test
    fun `a week-long span marks days`() {
        val start = epoch("2026-01-01T00:00:00Z")
        val end = epoch("2026-01-08T00:00:00Z")
        val marks = chartTimeMarkers(start, end, utc)
        assertTrue("expected roughly one mark per day, got ${marks.size}", marks.size in 5..9)
        marks.forEach { assertTrue(it in start..end) }
    }

    @Test
    fun `a month-long span marks dates, not crowded daily ticks`() {
        val start = epoch("2026-01-01T00:00:00Z")
        val end = epoch("2026-01-31T00:00:00Z")
        val marks = chartTimeMarkers(start, end, utc)
        assertTrue("expected a handful of marks, got ${marks.size}", marks.size in 2..7)
        marks.forEach { assertTrue(it in start..end) }
    }

    @Test
    fun `an empty or backwards span produces no time markers`() {
        assertEquals(emptyList<Long>(), chartTimeMarkers(1_000L, 1_000L))
        assertEquals(emptyList<Long>(), chartTimeMarkers(2_000L, 1_000L))
    }

    // -- formatAxisTimestamp --------------------------------------------------------------

    @Test
    fun `a day-long span formats as a bare hour figure`() {
        val t = epoch("2026-01-01T18:00:00Z")
        assertEquals("18h", formatAxisTimestamp(t, spanSeconds = 86_400L, zone = utc))
    }

    @Test
    fun `a week-long span formats as a compact day-month figure`() {
        val t = epoch("2026-03-06T00:00:00Z")
        assertEquals("6/3", formatAxisTimestamp(t, spanSeconds = 7 * 86_400L, zone = utc))
    }
}
