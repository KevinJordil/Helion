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

    /**
     * Pins the actual bug: HomeScreen used to gate the open-sync behind
     * `LaunchedEffect(Unit)`, which only ever fires once per *composition* -- covering a
     * cold start but not the far more common "reopen an already-running app from recents",
     * which resumes the existing Activity without recomposing Accueil. The fix moved the
     * trigger to `LifecycleEventEffect(Lifecycle.Event.ON_RESUME)`, which fires on every
     * resume, cold start included. This gate is what makes repeated resumes safe: it is
     * the only thing standing between "fires once per composition" and "fires once per
     * resume", so its repeated-resume behaviour is what actually needs pinning here --
     * simulating a cold start followed by two quick re-opens (which must not re-sync) and
     * then a much later one (which must).
     */
    @Test
    fun `simulated cold start then two quick reopens sync once, a later reopen syncs again`() {
        val gate = OpenSyncGate(minIntervalSeconds = 600)

        // Cold start: ON_RESUME fires for the first time ever.
        assertEquals(true, gate.shouldSync(nowSeconds = 10_000))
        gate.recordAttempt(nowSeconds = 10_000)

        // Backgrounded and reopened from recents twice in the next couple of minutes:
        // ON_RESUME fires again each time, but neither should trigger a second sync.
        assertEquals(false, gate.shouldSync(nowSeconds = 10_030))
        assertEquals(false, gate.shouldSync(nowSeconds = 10_200))

        // Reopened again the next morning: well past the debounce window.
        assertEquals(true, gate.shouldSync(nowSeconds = 10_000 + 3_600))
    }
}
