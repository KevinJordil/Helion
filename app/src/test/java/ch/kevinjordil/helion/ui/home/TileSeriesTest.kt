package ch.kevinjordil.helion.ui.home

import ch.kevinjordil.helion.ui.metric.MetricCatalog
import ch.kevinjordil.helion.ui.metric.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileSeriesTest {

    private val line = TileSpec(TileVisual.LINE_DAY, minSpan = 30.0)

    @Test
    fun `an empty bucket stays empty instead of being bridged`() {
        val readings = listOf(Reading(0, 60.0), Reading(250, 70.0))
        val series = tileSeries(readings, windowStart = 0, windowEnd = 300, buckets = 3, spec = line)

        assertEquals(3, series.size)
        assertNull(series[1])
        assertEquals(1f / 6f, series[0]!!.x, 0.0001f)
    }

    @Test
    fun `a calm series is drawn on at least the minimum span, centred`() {
        val readings = listOf(Reading(0, 61.0), Reading(150, 64.0))
        val series = tileSeries(readings, 0, 300, 2, line)

        // 3 bpm of difference over a 30 bpm span, centred on the middle.
        assertEquals(0.45f, series[0]!!.y, 0.001f)
        assertEquals(0.55f, series[1]!!.y, 0.001f)
    }

    @Test
    fun `a score with a real zero is drawn from its floor`() {
        val stress = TileSpec(TileVisual.BARS_DAY, minSpan = 100.0, floor = 0.0)
        val series = tileSeries(listOf(Reading(0, 25.0), Reading(150, 50.0)), 0, 300, 2, stress)

        assertEquals(0.25f, series[0]!!.y, 0.001f)
        assertEquals(0.5f, series[1]!!.y, 0.001f)
    }

    @Test
    fun `readings outside the window are ignored and a flat series sits mid-height`() {
        val series = tileSeries(listOf(Reading(-10, 1.0), Reading(10, 5.0), Reading(20, 5.0)), 0, 100, 1, TileSpec(TileVisual.LINE_DAY))

        assertEquals(0.5f, series.single()!!.y, 0.001f)
    }

    @Test
    fun `every catalog metric has a tile visual, and only steps and PAI track a goal`() {
        val goals = MetricCatalog.all.filter { tileSpec(it).visual == TileVisual.GOAL }.map { it.id }.toSet()
        assertEquals(setOf("steps", "pai"), goals)
        assertTrue(MetricCatalog.all.all { tileSpec(it).visual in TileVisual.entries })
    }
}
