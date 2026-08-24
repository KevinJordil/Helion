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

    private companion object {
        const val GADGETBRIDGE_PACKAGE = "nodomain.freeyourgadget.gadgetbridge"
        const val ACTION_ACTIVITY_SYNC = "$GADGETBRIDGE_PACKAGE.command.ACTIVITY_SYNC"
        const val ACTION_DATABASE_EXPORT = "$GADGETBRIDGE_PACKAGE.command.DATABASE_EXPORT"
    }
}
