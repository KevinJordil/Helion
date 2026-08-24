package ch.kevinjordil.helion.source

import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowApplication

@RunWith(RobolectricTestRunner::class)
class BroadcastExportSignalTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val shadow: ShadowApplication get() = shadowOf(context)

    // Baseline, not zero: dependencies such as WorkManager declare their own static
    // <receiver> entries, which Robolectric registers at app start-up independently of
    // anything this test does. What matters here is that BroadcastExportSignal does not
    // leave its *own* receiver behind, i.e. the count returns to wherever it started.
    private val baselineReceivers = shadow.registeredReceivers.size

    @Test
    fun `resolves Success on the export success broadcast and unregisters`() = runTest {
        val signal = BroadcastExportSignal(context)

        val outcome = signal.awaitExport(1_000) {
            context.sendBroadcast(Intent(GadgetbridgeCommands.ACTION_DATABASE_EXPORT_SUCCESS))
            shadowOf(Looper.getMainLooper()).idle()
        }

        assertEquals(ExportOutcome.Success, outcome)
        assertEquals(baselineReceivers, shadow.registeredReceivers.size)
    }

    @Test
    fun `resolves Failure on the export fail broadcast and unregisters`() = runTest {
        val signal = BroadcastExportSignal(context)

        val outcome = signal.awaitExport(1_000) {
            context.sendBroadcast(Intent(GadgetbridgeCommands.ACTION_DATABASE_EXPORT_FAIL))
            shadowOf(Looper.getMainLooper()).idle()
        }

        assertEquals(ExportOutcome.Failure, outcome)
        assertEquals(baselineReceivers, shadow.registeredReceivers.size)
    }

    @Test
    fun `resolves Timeout when nothing arrives in time and unregisters`() = runTest {
        val signal = BroadcastExportSignal(context)

        val outcome = signal.awaitExport(1_000) {
            // Nothing broadcast: simulates Gadgetbridge staying silent.
        }

        assertEquals(ExportOutcome.Timeout, outcome)
        assertEquals(baselineReceivers, shadow.registeredReceivers.size)
    }

    @Test
    fun `the receiver is registered before trigger runs, so an immediate broadcast is not missed`() = runTest {
        val signal = BroadcastExportSignal(context)
        var registeredWhenTriggered = false

        val outcome = signal.awaitExport(1_000) {
            registeredWhenTriggered = shadow.registeredReceivers.isNotEmpty()
            context.sendBroadcast(Intent(GadgetbridgeCommands.ACTION_DATABASE_EXPORT_SUCCESS))
            shadowOf(Looper.getMainLooper()).idle()
        }

        assertEquals(true, registeredWhenTriggered)
        assertEquals(ExportOutcome.Success, outcome)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelling the wait still unregisters the receiver`() = runTest {
        val signal = BroadcastExportSignal(context)

        val job = launch {
            signal.awaitExport(60_000) {
                // Never broadcasts: the wait is cancelled from the outside before
                // anything arrives, e.g. WorkManager stopping the worker mid-pass.
            }
        }
        // runCurrent(), not advanceUntilIdle(): the launched coroutine must only run
        // up to its suspension point (registering the receiver, then waiting on the
        // 60s timeout), without fast-forwarding virtual time through that timeout --
        // which is exactly what advanceUntilIdle() would do, completing the wait via
        // Timeout before this test ever gets to observe the mid-wait registration.
        runCurrent()
        assertEquals(baselineReceivers + 1, shadow.registeredReceivers.size)

        job.cancelAndJoin()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(baselineReceivers, shadow.registeredReceivers.size)
    }
}
