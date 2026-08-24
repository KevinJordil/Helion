package ch.kevinjordil.helion.ui.metric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricCatalogTest {

    @Test
    fun `every metric has a unique id`() {
        val ids = MetricCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `metrics can be looked up by id`() {
        assertEquals("heart_rate", MetricCatalog.byId("heart_rate").id)
    }

    @Test
    fun `unknown id fails loudly rather than returning null`() {
        try {
            MetricCatalog.byId("does_not_exist")
            org.junit.Assert.fail("expected an exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("does_not_exist"))
        }
    }

    @Test
    fun `the catalog covers every series the reader produces`() {
        val ids = MetricCatalog.all.map { it.id }
        assertTrue(ids.containsAll(listOf("heart_rate", "steps", "stress", "spo2", "pai", "hrv", "temperature")))
        assertEquals(7, ids.size)
    }

    @Test
    fun `only steps aggregates as a daily sum`() {
        val aggregated = MetricCatalog.all.filter { it.source.aggregation == Aggregation.DAILY_SUM }
        assertEquals(listOf("steps"), aggregated.map { it.id })
    }

    @Test
    fun `only temperature has a plausible range`() {
        val withRange = MetricCatalog.all.filter { it.plausibleRange != null }
        assertEquals(listOf("temperature"), withRange.map { it.id })
    }
}
