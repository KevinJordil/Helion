package ch.kevinjordil.helion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreshnessTest {

    @Test
    fun `null when nothing was ever synced`() {
        assertNull(minutesSinceLastSync(lastSyncAttempt = null, nowSeconds = 1_000))
    }

    @Test
    fun `expressed in whole minutes, rounded down`() {
        assertEquals(2, minutesSinceLastSync(lastSyncAttempt = 880, nowSeconds = 1_000))
    }

    @Test
    fun `zero right after a sync`() {
        assertEquals(0, minutesSinceLastSync(lastSyncAttempt = 1_000, nowSeconds = 1_000))
    }

    @Test
    fun `never negative even if the attempt timestamp is in the future`() {
        assertEquals(0, minutesSinceLastSync(lastSyncAttempt = 2_000, nowSeconds = 1_000))
    }
}
