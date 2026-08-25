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
class BroadcastSyncSignalTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val shadow: ShadowApplication get() = shadowOf(context)
    private val baselineReceivers = shadow.registeredReceivers.size

    @Test
    fun `resolves Finished on the sync-finish broadcast and unregisters`() = runTest {
        val signal = BroadcastSyncSignal(context)

        val outcome = signal.awaitSyncFinish(1_000) {
            context.sendBroadcast(Intent(GadgetbridgeCommands.ACTION_ACTIVITY_SYNC_FINISH))
            shadowOf(Looper.getMainLooper()).idle()
        }

        assertEquals(SyncFinishOutcome.Finished, outcome)
        assertEquals(baselineReceivers, shadow.registeredReceivers.size)
    }

    @Test
    fun `resolves Timeout when nothing arrives in time and unregisters`() = runTest {
        val signal = BroadcastSyncSignal(context)

        val outcome = signal.awaitSyncFinish(1_000) {
            // Nothing broadcast: simulates a device that never confirms the sync.
        }

        assertEquals(SyncFinishOutcome.Timeout, outcome)
        assertEquals(baselineReceivers, shadow.registeredReceivers.size)
    }

    @Test
    fun `the receiver is registered before trigger runs, so an immediate broadcast is not missed`() = runTest {
        val signal = BroadcastSyncSignal(context)
        var registeredWhenTriggered = false

        val outcome = signal.awaitSyncFinish(1_000) {
            registeredWhenTriggered = shadow.registeredReceivers.isNotEmpty()
            context.sendBroadcast(Intent(GadgetbridgeCommands.ACTION_ACTIVITY_SYNC_FINISH))
            shadowOf(Looper.getMainLooper()).idle()
        }

        assertEquals(true, registeredWhenTriggered)
        assertEquals(SyncFinishOutcome.Finished, outcome)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelling the wait still unregisters the receiver`() = runTest {
        val signal = BroadcastSyncSignal(context)

        val job = launch {
            signal.awaitSyncFinish(60_000) {
                // Never broadcasts: the wait is cancelled from the outside.
            }
        }
        runCurrent()
        assertEquals(baselineReceivers + 1, shadow.registeredReceivers.size)

        job.cancelAndJoin()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(baselineReceivers, shadow.registeredReceivers.size)
    }
}
