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
    fun `no two metrics share a colour`() {
        val colors = MetricCatalog.all.map { HelionDarkColors.metricColor(it) }
        assertEquals(colors.size, colors.toSet().size)
    }

    @Test
    fun `a metric's colour is the same in both themes`() {
        MetricCatalog.all.forEach { metric ->
            assertEquals(HelionDarkColors.metricColor(metric), HelionLightColors.metricColor(metric))
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
