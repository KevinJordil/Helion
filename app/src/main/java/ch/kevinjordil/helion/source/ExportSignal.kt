package ch.kevinjordil.helion.source

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlin.coroutines.resume
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** Result of waiting for Gadgetbridge to finish (or fail) a triggered export. */
sealed interface ExportOutcome {
    data object Success : ExportOutcome
    data object Failure : ExportOutcome
    data object Timeout : ExportOutcome
}

/**
 * Indirection so Ingestor can wait for Gadgetbridge's export-completion broadcast
 * without depending on Android's BroadcastReceiver machinery directly -- mirrors the
 * CommandSender pattern used by GadgetbridgeCommands. This keeps Ingestor testable
 * with a plain fake, while the real broadcast listening lives in a separate class.
 */
interface ExportSignal {

    /**
     * Starts listening for the export-completion broadcasts, invokes [trigger] once
     * listening is guaranteed to be in place (so a broadcast that fires immediately
     * cannot be missed), then suspends until an outcome arrives or [timeoutMillis]
     * elapses. Never waits unboundedly: a caller that goes silent must still get an
     * answer so a periodic retry can happen.
     */
    suspend fun awaitExport(timeoutMillis: Long, trigger: () -> Unit): ExportOutcome
}

/**
 * Listens for Gadgetbridge's DATABASE_EXPORT_SUCCESS / DATABASE_EXPORT_FAIL broadcasts
 * using a real BroadcastReceiver. The receiver is registered before [trigger] runs and
 * is always unregistered before returning -- on success, on failure, on timeout, and on
 * any other exception -- so a cancelled or failed ingestion pass never leaks it.
 */
class BroadcastExportSignal(private val context: Context) : ExportSignal {

    override suspend fun awaitExport(timeoutMillis: Long, trigger: () -> Unit): ExportOutcome {
        var receiver: BroadcastReceiver? = null
        return try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    val r = object : BroadcastReceiver() {
                        override fun onReceive(receiverContext: Context, intent: Intent) {
                            val outcome = when (intent.action) {
                                GadgetbridgeCommands.ACTION_DATABASE_EXPORT_SUCCESS -> ExportOutcome.Success
                                GadgetbridgeCommands.ACTION_DATABASE_EXPORT_FAIL -> ExportOutcome.Failure
                                else -> return
                            }
                            if (continuation.isActive) continuation.resume(outcome)
                        }
                    }
                    receiver = r

                    val filter = IntentFilter().apply {
                        addAction(GadgetbridgeCommands.ACTION_DATABASE_EXPORT_SUCCESS)
                        addAction(GadgetbridgeCommands.ACTION_DATABASE_EXPORT_FAIL)
                    }

                    // Gadgetbridge is a separate app: on API 33+ receiving its broadcasts
                    // requires the explicit RECEIVER_EXPORTED flag, or registration is
                    // silently rejected at runtime.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
                    } else {
                        @Suppress("UnspecifiedRegisterReceiverFlag")
                        context.registerReceiver(r, filter)
                    }

                    trigger()
                }
            }
        } catch (e: TimeoutCancellationException) {
            ExportOutcome.Timeout
        } finally {
            receiver?.let { context.unregisterReceiver(it) }
        }
    }
}
