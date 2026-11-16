package ch.kevinjordil.helion.ui.metric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailChartTest {

    @Test
    fun `zooming in around an anchor keeps that point under the fingers`() {
        val zoomed = ChartViewport().transformed(factor = 2f, anchor = 0.5f, panFraction = 0f, minWidth = 0.01f)

        assertEquals(0.25f, zoomed.start, 0.0001f)
        assertEquals(0.75f, zoomed.end, 0.0001f)
    }

    @Test
    fun `zoom stops at the minimum width and never leaves the domain`() {
        val deep = ChartViewport().transformed(factor = 1000f, anchor = 1f, panFraction = 0f, minWidth = 0.1f)
        assertEquals(0.1f, deep.width, 0.0001f)
        assertEquals(1f, deep.end, 0.0001f)

        val panned = deep.transformed(factor = 1f, anchor = 0.5f, panFraction = -50f, minWidth = 0.1f)
        assertEquals(1f, panned.end, 0.0001f)
        assertTrue(panned.start >= 0f)
    }

    @Test
    fun `zooming out past the full span is the full span`() {
        val out = ChartViewport(0.4f, 0.6f).transformed(factor = 0.01f, anchor = 0.5f, panFraction = 0f, minWidth = 0.05f)
        assertTrue(out.isFull)
    }

    @Test
    fun `a per-minute series breaks where the strap was off, not between samples`() {
        val minutes = (0L until 30L).map { Reading(it * 60, 60.0) } + (60L until 90L).map { Reading(it * 60, 60.0) }
        val threshold = lineGapThreshold(minutes)

        assertEquals(15 * 60L, threshold)
        assertTrue(30 * 60L > threshold)
    }

    @Test
    fun `French notation, with thousands grouped from ten thousand up`() {
        val steps = MetricCatalog.byId("steps")!!
        val temperature = MetricCatalog.byId("temperature")!!

        assertEquals("8000", steps.formatValue(8000.0))
        assertEquals("10 520", steps.formatValue(10_520.0))
        assertEquals("123 456", steps.formatValue(123_456.0))
        assertEquals("36,5", temperature.formatValue(36.5))
    }
}
