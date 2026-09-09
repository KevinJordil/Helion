package ch.kevinjordil.helion.ui.metric

import ch.kevinjordil.helion.ui.theme.HelionDarkColors
import ch.kevinjordil.helion.ui.theme.HelionLightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MetricColorTest {

    @Test
    fun `every catalog metric resolves to a colour`() {
        MetricCatalog.all.forEach { metric ->
            assertNotNull(HelionDarkColors.metricColor(metric))
        }
    }

    @Test
    fun `no two metrics share a colour, in either theme`() {
        listOf(HelionDarkColors, HelionLightColors).forEach { theme ->
            val colors = MetricCatalog.all.map { theme.metricColor(it) }
            assertEquals(colors.size, colors.toSet().size)
        }
    }

    @Test
    fun `a metric keeps its slot in the palette across both themes`() {
        // The two themes no longer share RGB values -- the light theme's hues are tuned to
        // carry against white and read as muted and heavy on the dark ground, so the dark
        // theme has its own raised set (MetricPalette.OnDark). What must not vary is which
        // hue a metric gets: heart rate is the red one in both, steps the blue one in both.
        // So this asserts the slot, not the value.
        MetricCatalog.all.forEach { metric ->
            val darkSlot = HelionDarkColors.metricHues.indexOf(HelionDarkColors.metricColor(metric))
            val lightSlot = HelionLightColors.metricHues.indexOf(HelionLightColors.metricColor(metric))
            assertEquals("slot for ${'$'}{metric.id}", darkSlot, lightSlot)
        }
    }

    @Test
    fun `a metric's colour does not depend on where it sits in the catalog`() {
        // Reversing the catalog's order must not change any metric's colour: the
        // assignment is keyed by id, not by position in whatever list happens to be
        // iterated -- see MetricColor.kt's own kdoc.
        val originalColors = MetricCatalog.all.associate { it.id to HelionDarkColors.metricColor(it) }
        val reversed = MetricCatalog.all.reversed()
        reversed.forEach { metric ->
            assertEquals(originalColors.getValue(metric.id), HelionDarkColors.metricColor(metric))
        }
    }

    @Test
    fun `heart rate and steps keep their own distinct, permanent hues`() {
        val heartRate = HelionDarkColors.metricColor(MetricCatalog.byId("heart_rate")!!)
        val steps = HelionDarkColors.metricColor(MetricCatalog.byId("steps")!!)
        assert(heartRate != steps)
    }
}
