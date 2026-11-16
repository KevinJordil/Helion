package ch.kevinjordil.helion.ui.home

import ch.kevinjordil.helion.ui.metric.MetricCatalog
import ch.kevinjordil.helion.ui.metric.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileSeriesTest {

    private val line = TileSpec(TileVisual.LINE, minSpan = 30.0)

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
        val stress = TileSpec(TileVisual.BARS, minSpan = 100.0, floor = 0.0)
        val series = tileSeries(listOf(Reading(0, 25.0), Reading(150, 50.0)), 0, 300, 2, stress)

        assertEquals(0.25f, series[0]!!.y, 0.001f)
        assertEquals(0.5f, series[1]!!.y, 0.001f)
    }

    @Test
    fun `readings outside the window are ignored and a flat series sits mid-height`() {
        val series = tileSeries(listOf(Reading(-10, 1.0), Reading(10, 5.0), Reading(20, 5.0)), 0, 100, 1, TileSpec(TileVisual.LINE))

        assertEquals(0.5f, series.single()!!.y, 0.001f)
    }

    @Test
    fun `every catalog metric has a tile visual, and only steps and PAI track a goal`() {
        val goals = MetricCatalog.all.filter { tileSpec(it).visual == TileVisual.GOAL }.map { it.id }.toSet()
        assertEquals(setOf("steps", "pai"), goals)
        assertTrue(MetricCatalog.all.all { tileSpec(it).visual in TileVisual.entries })
    }

    // -- planTile: each tile's window, and a chart that follows what was recorded ---------

    private val now = 30 * DAY

    /** One reading every [stepMinutes] over the last [hours] hours, up to [now]. */
    private fun recent(hours: Int, stepMinutes: Int = 1) =
        (0 until hours * 60 step stepMinutes).map { Reading(now - 1 - it * 60L, 60.0) }

    @Test
    fun `minute-by-minute recent hours stay a line over the last six hours`() {
        val plan = planTile(line, recent(6), now)
        assertEquals(TileVisual.LINE, plan.visual)
        assertEquals(TileWindow.RECENT, plan.window)
        assertEquals(now - RECENT_WINDOW_SECONDS, plan.start)
    }

    @Test
    fun `sparse readings still draw the metric's own chart, never dots`() {
        assertEquals(TileVisual.LINE, planTile(line, recent(6, stepMinutes = 40), now).visual)
        val bars = TileSpec(TileVisual.BARS, minSpan = 50.0, floor = 0.0)
        assertEquals(TileVisual.BARS, planTile(bars, recent(6, stepMinutes = 5), now).visual)
        assertEquals(TileVisual.BARS, planTile(bars, recent(2, stepMinutes = 5), now).visual)
    }

    @Test
    fun `a nightly metric covers last night, not the empty recent hours`() {
        val nightly = TileSpec(TileVisual.LINE, TileWindow.LAST_NIGHT, minSpan = 20.0)
        val night = (now - 16 * HOUR)..(now - 8 * HOUR)
        val readings = (0 until 8 * 60).map { Reading(night.first + it * 60L, 50.0) }

        val plan = planTile(nightly, readings, now, lastNight = night)
        assertEquals(TileWindow.LAST_NIGHT, plan.window)
        assertEquals(TileVisual.LINE, plan.visual)
        assertEquals(night.first, plan.start)
        assertEquals(night.last, plan.end)
        // Over the last six hours the same readings would have shown nothing at all.
        assertEquals(TileVisual.NONE, planTile(line, readings, now).visual)
    }

    @Test
    fun `a day-long metric ignores the night, and no known night falls back to the recent hours`() {
        val night = (now - 16 * HOUR)..(now - 8 * HOUR)
        assertEquals(TileWindow.RECENT, planTile(line, recent(6), now, lastNight = night).window)
        val nightly = TileSpec(TileVisual.LINE, TileWindow.LAST_NIGHT)
        assertEquals(TileWindow.RECENT, planTile(nightly, recent(6), now, lastNight = null).window)
    }

    @Test
    fun `the strap's sleep-only metrics use the night, the rest the recent hours`() {
        val byWindow = MetricCatalog.all.filter { tileSpec(it).visual != TileVisual.GOAL }
            .groupBy({ tileSpec(it).window }, { it.id })
        assertEquals(setOf("hrv", "respiratory_rate", "spo2"), byWindow[TileWindow.LAST_NIGHT].orEmpty().toSet())
        assertEquals(setOf("heart_rate", "stress", "temperature"), byWindow[TileWindow.RECENT].orEmpty().toSet())
    }

    @Test
    fun `values without a real zero never draw bars`() {
        // Bars claim a proportion from zero: only a metric with a floor may use them.
        MetricCatalog.all.map(::tileSpec).filter { it.visual == TileVisual.BARS }.forEach {
            assertEquals(0.0, it.floor)
        }
    }

    @Test
    fun `nothing in the window draws no chart, and a goal is always a goal`() {
        assertEquals(TileVisual.NONE, planTile(line, listOf(Reading(now - 20 * DAY, 60.0)), now).visual)
        assertEquals(TileVisual.GOAL, planTile(TileSpec(TileVisual.GOAL), emptyList(), now).visual)
    }

    @Test
    fun `an age reads in minutes, hours, then days`() {
        assertEquals("35 min", formatAge(35 * 60L))
        assertEquals("11 h", formatAge(11 * HOUR + 20 * 60))
        assertEquals("3 j", formatAge(3 * DAY + HOUR))
    }

    private companion object {
        const val DAY = 86_400L
        const val HOUR = 3_600L
    }
}
