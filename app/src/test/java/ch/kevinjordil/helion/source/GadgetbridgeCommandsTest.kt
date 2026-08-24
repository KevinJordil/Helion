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
            "nodomain.freeyourgadget.gadgetbridge.command.TRIGGER_DATABASE_EXPORT",
            sender.sent.single().action,
        )
    }

    @Test
    fun `intents target Gadgetbridge explicitly`() {
        val sender = RecordingSender()
        GadgetbridgeCommands(sender).requestSync()
        assertEquals("nodomain.freeyourgadget.gadgetbridge", sender.sent.single().`package`)
    }

    @Test
    fun `database export success action has the documented value`() {
        assertEquals(
            "nodomain.freeyourgadget.gadgetbridge.action.DATABASE_EXPORT_SUCCESS",
            GadgetbridgeCommands.ACTION_DATABASE_EXPORT_SUCCESS,
        )
    }

    @Test
    fun `database export fail action has the documented value`() {
        assertEquals(
            "nodomain.freeyourgadget.gadgetbridge.action.DATABASE_EXPORT_FAIL",
            GadgetbridgeCommands.ACTION_DATABASE_EXPORT_FAIL,
        )
    }

    @Test
    fun `activity sync finish action has the documented value`() {
        assertEquals(
            "nodomain.freeyourgadget.gadgetbridge.action.ACTIVITY_SYNC_FINISH",
            GadgetbridgeCommands.ACTION_ACTIVITY_SYNC_FINISH,
        )
    }
}
