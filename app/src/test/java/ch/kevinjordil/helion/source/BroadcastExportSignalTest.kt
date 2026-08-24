package ch.kevinjordil.helion.source

import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
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

    @Test
    fun `resolves Success on the export success broadcast and unregisters`() = runTest {
        val signal = BroadcastExportSignal(context)

        val outcome = signal.awaitExport(1_000) {
            context.sendBroadcast(Intent(GadgetbridgeCommands.ACTION_DATABASE_EXPORT_SUCCESS))
            shadowOf(Looper.getMainLooper()).idle()
        }

        assertEquals(ExportOutcome.Success, outcome)
        assertEquals(0, shadow.registeredReceivers.size)
    }

    @Test
    fun `resolves Failure on the export fail broadcast and unregisters`() = runTest {
        val signal = BroadcastExportSignal(context)

        val outcome = signal.awaitExport(1_000) {
            context.sendBroadcast(Intent(GadgetbridgeCommands.ACTION_DATABASE_EXPORT_FAIL))
            shadowOf(Looper.getMainLooper()).idle()
        }

        assertEquals(ExportOutcome.Failure, outcome)
        assertEquals(0, shadow.registeredReceivers.size)
    }

    @Test
    fun `resolves Timeout when nothing arrives in time and unregisters`() = runTest {
        val signal = BroadcastExportSignal(context)

        val outcome = signal.awaitExport(1_000) {
            // Nothing broadcast: simulates Gadgetbridge staying silent.
        }

        assertEquals(ExportOutcome.Timeout, outcome)
        assertEquals(0, shadow.registeredReceivers.size)
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
}
