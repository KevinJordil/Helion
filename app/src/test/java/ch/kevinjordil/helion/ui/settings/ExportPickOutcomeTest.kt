package ch.kevinjordil.helion.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportPickOutcomeTest {

    @Test
    fun `a permission grant that succeeds resolves to Granted`() {
        val outcome = resolveExportPick(takePermission = { /* no-op: succeeds */ })
        assertEquals(ExportPickOutcome.Granted, outcome)
    }

    @Test
    fun `a SecurityException from taking the grant resolves to Refused, not a crash`() {
        val outcome = resolveExportPick(takePermission = { throw SecurityException("no persistable grants here") })
        assertEquals(ExportPickOutcome.Refused, outcome)
    }
}
