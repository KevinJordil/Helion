package ch.kevinjordil.helion.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeStatusTest {

    @Test
    fun `no export configured wins over everything else`() {
        assertEquals(HomeStatus.NoSource, resolveHomeStatus(exportConfigured = false, hasAnyStoredSample = false))
        assertEquals(HomeStatus.NoSource, resolveHomeStatus(exportConfigured = false, hasAnyStoredSample = true))
    }

    @Test
    fun `configured but nothing stored yet is EmptyArchive, not NoSource`() {
        assertEquals(HomeStatus.EmptyArchive, resolveHomeStatus(exportConfigured = true, hasAnyStoredSample = false))
    }

    @Test
    fun `configured with data is the nominal dashboard state`() {
        assertEquals(HomeStatus.Nominal, resolveHomeStatus(exportConfigured = true, hasAnyStoredSample = true))
    }
}
