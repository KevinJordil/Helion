package ch.kevinjordil.helion.ui.home

import ch.kevinjordil.helion.ui.settings.SyncOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundSyncTest {

    private var clock = 10_000L
    private var configured = true
    private var passes = 0

    private fun TestScope.foregroundSync(
        gate: OpenSyncGate = OpenSyncGate(),
        pass: suspend (onPhase: (RefreshPhase) -> Unit) -> SyncOutcome = { onPhase ->
            passes++
            onPhase(RefreshPhase.SYNCING)
            onPhase(RefreshPhase.READING)
            onPhase(RefreshPhase.DONE)
            SyncOutcome.Ingested(minutes = 5, points = 1)
        },
    ) = ForegroundSync(
        scope = this,
        gate = gate,
        isConfigured = { configured },
        nowSeconds = { clock },
        runPass = pass,
    )

    @Test
    fun `opening the app runs one pass and publishes its outcome`() = runTest {
        val sync = foregroundSync()

        assertTrue(sync.onAppOpened())
        advanceUntilIdle()

        assertEquals(1, passes)
        assertEquals(ForegroundSyncState(phase = null, lastOutcome = SyncOutcome.Ingested(5, 1), completedPasses = 1), sync.state.value)
    }

    @Test
    fun `nothing runs on open while no export file is configured`() = runTest {
        configured = false
        val sync = foregroundSync()

        assertFalse(sync.onAppOpened())
        advanceUntilIdle()

        assertEquals(0, passes)
    }

    @Test
    fun `a quick return to the app does not sync again, a later one does`() = runTest {
        val sync = foregroundSync()
        sync.onAppOpened()
        advanceUntilIdle()

        clock += 60
        assertFalse(sync.onAppOpened())
        clock += OpenSyncGate.DEFAULT_MIN_INTERVAL_SECONDS
        assertTrue(sync.onAppOpened())
        advanceUntilIdle()

        assertEquals(2, passes)
    }

    @Test
    fun `asking again while a pass runs joins it instead of starting a second one`() = runTest {
        val release = CompletableDeferred<Unit>()
        val sync = foregroundSync(pass = { onPhase ->
            passes++
            onPhase(RefreshPhase.SYNCING)
            release.await()
            SyncOutcome.Ingested(1, 0)
        })

        val first = sync.syncNow()
        runCurrent()
        val second = sync.syncNow()

        assertSame(first, second)
        assertEquals(RefreshPhase.SYNCING, sync.state.value.phase)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, passes)
        assertNull(sync.state.value.phase)
    }

    @Test
    fun `a manual sync counts as the latest attempt for the opening debounce`() = runTest {
        val sync = foregroundSync()
        sync.syncNow()
        advanceUntilIdle()

        clock += 60
        assertFalse(sync.onAppOpened())
    }

    @Test
    fun `an unexpected exception becomes a Failed outcome and clears the phase`() = runTest {
        val sync = foregroundSync(pass = { throw IllegalStateException("receiver refused") })

        val outcome = sync.syncNow().await()

        assertEquals(SyncOutcome.Failed("receiver refused"), outcome)
        assertNull(sync.state.value.phase)
        assertEquals(1, sync.state.value.completedPasses)
    }
}
