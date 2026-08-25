package ch.kevinjordil.helion.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenSyncGateTest {

    @Test
    fun `the first ever check always syncs`() {
        val gate = OpenSyncGate(minIntervalSeconds = 600)
        assertEquals(true, gate.shouldSync(nowSeconds = 1_000))
    }

    @Test
    fun `immediately after recording an attempt, a re-check within the interval does not sync`() {
        val gate = OpenSyncGate(minIntervalSeconds = 600)
        gate.recordAttempt(nowSeconds = 1_000)
        assertEquals(false, gate.shouldSync(nowSeconds = 1_001))
        assertEquals(false, gate.shouldSync(nowSeconds = 1_000 + 599))
    }

    @Test
    fun `once the interval has fully elapsed, it syncs again`() {
        val gate = OpenSyncGate(minIntervalSeconds = 600)
        gate.recordAttempt(nowSeconds = 1_000)
        assertEquals(true, gate.shouldSync(nowSeconds = 1_000 + 600))
        assertEquals(true, gate.shouldSync(nowSeconds = 1_000 + 601))
    }

    @Test
    fun `recording a new attempt resets the debounce window`() {
        val gate = OpenSyncGate(minIntervalSeconds = 600)
        gate.recordAttempt(nowSeconds = 1_000)
        gate.recordAttempt(nowSeconds = 1_500)
        assertEquals(false, gate.shouldSync(nowSeconds = 1_600))
        assertEquals(true, gate.shouldSync(nowSeconds = 1_500 + 600))
    }
}
