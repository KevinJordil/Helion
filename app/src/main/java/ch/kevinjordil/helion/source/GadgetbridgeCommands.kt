package ch.kevinjordil.helion.source

import android.content.Context
import android.content.Intent

/** Indirection so intent construction can be tested without a real broadcast. */
interface CommandSender {
    fun send(intent: Intent)
}

class BroadcastCommandSender(private val context: Context) : CommandSender {
    override fun send(intent: Intent) = context.sendBroadcast(intent)
}

/**
 * Drives Gadgetbridge through its Intent API. The user must enable it under
 * Settings > Developer options > Intent API, and enable the matching categories.
 */
class GadgetbridgeCommands(private val sender: CommandSender) {

    fun requestSync() = sender.send(intentFor(ACTION_ACTIVITY_SYNC))

    fun requestExport() = sender.send(intentFor(ACTION_DATABASE_EXPORT))

    private fun intentFor(action: String) = Intent(action).apply {
        `package` = GADGETBRIDGE_PACKAGE
    }

    // These action strings are taken from Gadgetbridge's Intent API documentation and have
    // not been verified against a running Gadgetbridge install (no device is available in
    // this environment). They are deliberately kept in this one place, and only here, so
    // that a correction -- like this one -- is a one-file fix.
    companion object {
        const val GADGETBRIDGE_PACKAGE = "nodomain.freeyourgadget.gadgetbridge"
        const val ACTION_ACTIVITY_SYNC = "$GADGETBRIDGE_PACKAGE.command.ACTIVITY_SYNC"
        const val ACTION_DATABASE_EXPORT = "$GADGETBRIDGE_PACKAGE.command.TRIGGER_DATABASE_EXPORT"

        /** Broadcast by Gadgetbridge when a triggered database export completes successfully. */
        const val ACTION_DATABASE_EXPORT_SUCCESS = "$GADGETBRIDGE_PACKAGE.action.DATABASE_EXPORT_SUCCESS"

        /** Broadcast by Gadgetbridge when a triggered database export fails. */
        const val ACTION_DATABASE_EXPORT_FAIL = "$GADGETBRIDGE_PACKAGE.action.DATABASE_EXPORT_FAIL"

        /** Broadcast by Gadgetbridge when a triggered activity sync finishes. */
        const val ACTION_ACTIVITY_SYNC_FINISH = "$GADGETBRIDGE_PACKAGE.action.ACTIVITY_SYNC_FINISH"
    }
}
