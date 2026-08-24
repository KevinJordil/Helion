package ch.kevinjordil.helion.source

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GadgetbridgeCommandsTest {

    private class RecordingSender : CommandSender {
        val sent = mutableListOf<Intent>()
        override fun send(intent: Intent) {
            sent += intent
        }
    }

    @Test
    fun `requestSync sends the activity sync action`() {
        val sender = RecordingSender()
        GadgetbridgeCommands(sender).requestSync()
        assertEquals(
            "nodomain.freeyourgadget.gadgetbridge.command.ACTIVITY_SYNC",
            sender.sent.single().action,
        )
    }

    @Test
    fun `requestExport sends the export action`() {
        val sender = RecordingSender()
        GadgetbridgeCommands(sender).requestExport()
        assertEquals(
            "nodomain.freeyourgadget.gadgetbridge.command.DATABASE_EXPORT",
            sender.sent.single().action,
        )
    }

    @Test
    fun `intents target Gadgetbridge explicitly`() {
        val sender = RecordingSender()
        GadgetbridgeCommands(sender).requestSync()
        assertEquals("nodomain.freeyourgadget.gadgetbridge", sender.sent.single().`package`)
    }
}
