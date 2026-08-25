package ch.kevinjordil.helion.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportSchemaTest {

    @Test
    fun `minute columns are ordered as the reader reads them`() {
        // ExportReader reads by cursor index, so this order is a contract, not a detail.
        assertEquals(ExportSchema.COL_TIMESTAMP, ExportSchema.MINUTE_COLUMNS[0])
        assertEquals(ExportSchema.COL_HEART_RATE, ExportSchema.MINUTE_COLUMNS[4])
        assertEquals(ExportSchema.COL_SLEEP, ExportSchema.MINUTE_COLUMNS.last())
        assertEquals(6, ExportSchema.MINUTE_COLUMNS.size)
    }

    @Test
    fun `table names are not empty`() {
        listOf(
            ExportSchema.TABLE_MINUTE,
            ExportSchema.TABLE_STRESS,
            ExportSchema.TABLE_SPO2,
            ExportSchema.TABLE_PAI,
            ExportSchema.TABLE_HRV,
            ExportSchema.TABLE_TEMPERATURE,
            ExportSchema.TABLE_SLEEP_SESSION,
        ).forEach { assertTrue(it.isNotBlank()) }
    }
}
