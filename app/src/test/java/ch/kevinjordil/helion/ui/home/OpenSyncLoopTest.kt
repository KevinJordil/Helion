package ch.kevinjordil.helion.ui.home

import ch.kevinjordil.helion.ui.settings.SyncOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSyncLoopTest {

    @Test
    fun `stops as soon as a pass ingests nothing new`() = runTest {
        val outcomes = listOf(
            SyncOutcome.Ingested(minutes = 5, points = 2),
            SyncOutcome.Ingested(minutes = 1, points = 0),
            SyncOutcome.Ingested(minutes = 0, points = 0),
            SyncOutcome.Ingested(minutes = 9, points = 9), // must never be reached
        ).iterator()
        var passes = 0
        val delays = mutableListOf<Long>()

        runOpenSyncLoop(
            maxPasses = 10,
            passDelayMillis = 90_000L,
            timeBudgetMillis = 1_000_000L,
            elapsedMillis = { 0L },
            delay = { millis -> delays += millis },
            runPass = { passes += 1; outcomes.next() },
        )

        assertEquals(3, passes)
        assertEquals(listOf(90_000L, 90_000L), delays)
    }

    @Test
    fun `stops at the pass cap even while every pass keeps finding new data`() = runTest {
        var passes = 0

        runOpenSyncLoop(
            maxPasses = 4,
            passDelayMillis = 1L,
            timeBudgetMillis = 1_000_000L,
            elapsedMillis = { 0L },
            delay = {},
            runPass = { passes += 1; SyncOutcome.Ingested(minutes = 1, points = 1) },
        )

        assertEquals(4, passes)
    }

    @Test
    fun `stops at the time budget even while every pass keeps finding new data`() = runTest {
        var passes = 0
        var fakeClock = 0L

        runOpenSyncLoop(
            maxPasses = 100,
            passDelayMillis = 1L,
            timeBudgetMillis = 250L,
            elapsedMillis = { fakeClock },
            delay = { fakeClock += 100L },
            runPass = { passes += 1; SyncOutcome.Ingested(minutes = 1, points = 1) },
        )

        // The fake clock advances 100ms per delay; with a 250ms budget the loop must stop
        // after a handful of passes, nowhere near the 100-pass cap.
        assertTrue("expected the time budget to cut this off well short of 100 passes, got $passes", passes < 100)
    }

    @Test
    fun `a non-Ingested outcome stops the loop without waiting for the cap`() = runTest {
        var passes = 0

        runOpenSyncLoop(
            maxPasses = 10,
            passDelayMillis = 1L,
            timeBudgetMillis = 1_000_000L,
            elapsedMillis = { 0L },
            delay = { error("must not delay: the loop should have stopped already") },
            runPass = { passes += 1; SyncOutcome.Failed("export unreadable") },
        )

        assertEquals(1, passes)
    }

    @Test
    fun `every pass's outcome is reported, in order`() = runTest {
        val reported = mutableListOf<SyncOutcome>()
        val outcomes = listOf(
            SyncOutcome.Ingested(minutes = 5, points = 2),
            SyncOutcome.Ingested(minutes = 0, points = 0),
        ).iterator()

        runOpenSyncLoop(
            maxPasses = 10,
            passDelayMillis = 1L,
            timeBudgetMillis = 1_000_000L,
            elapsedMillis = { 0L },
            delay = {},
            runPass = { outcomes.next() },
            onPass = { outcome -> reported += outcome },
        )

        assertEquals(
            listOf(SyncOutcome.Ingested(5, 2), SyncOutcome.Ingested(0, 0)),
            reported,
        )
    }

    @Test
    fun `a cancellation mid-loop propagates instead of being swallowed`() = runTest {
        var passes = 0
        val job = launch {
            runOpenSyncLoop(
                maxPasses = 10,
                passDelayMillis = 1L,
                timeBudgetMillis = 1_000_000L,
                elapsedMillis = { 0L },
                delay = { throw CancellationException("cancelled between passes") },
                runPass = { passes += 1; SyncOutcome.Ingested(minutes = 1, points = 1) },
            )
        }
        job.join()

        assertTrue(job.isCancelled)
        // The first pass ran and reported real progress before the delay threw; the loop
        // did not swallow the cancellation and quietly keep going.
        assertEquals(1, passes)
    }

    @Test
    fun `an external cancel stops the loop cleanly`() = runTest {
        var passes = 0
        val job = launch {
            runOpenSyncLoop(
                maxPasses = 10,
                passDelayMillis = 1L,
                timeBudgetMillis = 1_000_000L,
                elapsedMillis = { 0L },
                // The real suspending delay, not a no-op fake: cancellation is cooperative
                // and only takes effect at an actual suspension point, exactly as it would
                // in production between two real passes.
                delay = { millis -> delay(millis) },
                runPass = {
                    passes += 1
                    if (passes == 2) coroutineContext.cancel()
                    SyncOutcome.Ingested(minutes = 1, points = 1)
                },
            )
        }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(2, passes)
    }
}
