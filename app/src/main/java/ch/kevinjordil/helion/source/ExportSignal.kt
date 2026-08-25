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
 * Registers a [BroadcastReceiver] for [actions], invokes [trigger] once registration is
 * guaranteed to be in place (so a broadcast that fires immediately cannot be missed), then
 * suspends until [onAction] resolves one of the received actions to a non-null [T], or
 * [timeoutMillis] elapses. The receiver is always unregistered before returning -- on
 * success, on failure, on timeout, and on cancellation -- so a stopped caller never leaks
 * it.
 *
 * Shared by [BroadcastExportSignal] and [BroadcastSyncSignal]: both are "register, trigger,
 * wait for one of a small set of actions, with a bound" -- the exact same shape, just
 * different actions and a different outcome type. Extending this one mechanism, rather than
 * writing a second awaiting BroadcastReceiver from scratch, is what keeps that shape in one
 * place.
 */
internal suspend fun <T> awaitBroadcast(
    context: Context,
    actions: List<String>,
    timeoutMillis: Long,
    onTimeout: T,
    trigger: () -> Unit,
    onAction: (String) -> T?,
): T {
    var receiver: BroadcastReceiver? = null
    return try {
        withTimeout(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val r = object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context, intent: Intent) {
                        val outcome = intent.action?.let(onAction) ?: return
                        if (continuation.isActive) continuation.resume(outcome)
                    }
                }

                val filter = IntentFilter().apply { actions.forEach(::addAction) }

                // Gadgetbridge is a separate app: on API 33+ receiving its broadcasts
                // requires the explicit RECEIVER_EXPORTED flag, or registration is
                // silently rejected at runtime.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(r, filter)
                }
                // Only recorded once registration itself succeeded: if registerReceiver
                // throws, `receiver` must stay null so the `finally` below does not call
                // unregisterReceiver on a receiver the OS never registered (which would
                // itself throw and mask the original failure).
                receiver = r

                trigger()
            }
        }
    } catch (_: TimeoutCancellationException) {
        onTimeout
    } finally {
        receiver?.let { context.unregisterReceiver(it) }
    }
}

/**
 * Listens for Gadgetbridge's DATABASE_EXPORT_SUCCESS / DATABASE_EXPORT_FAIL broadcasts
 * using a real BroadcastReceiver. See [awaitBroadcast] for the shared registration and
 * timeout mechanics.
 */
class BroadcastExportSignal(private val context: Context) : ExportSignal {

    override suspend fun awaitExport(timeoutMillis: Long, trigger: () -> Unit): ExportOutcome =
        awaitBroadcast(
            context = context,
            actions = listOf(
                GadgetbridgeCommands.ACTION_DATABASE_EXPORT_SUCCESS,
                GadgetbridgeCommands.ACTION_DATABASE_EXPORT_FAIL,
            ),
            timeoutMillis = timeoutMillis,
            onTimeout = ExportOutcome.Timeout,
            trigger = trigger,
            onAction = { action ->
                when (action) {
                    GadgetbridgeCommands.ACTION_DATABASE_EXPORT_SUCCESS -> ExportOutcome.Success
                    GadgetbridgeCommands.ACTION_DATABASE_EXPORT_FAIL -> ExportOutcome.Failure
                    else -> null
                }
            },
        )
}

/** Result of waiting for Gadgetbridge to finish a triggered activity sync. */
sealed interface SyncFinishOutcome {
    data object Finished : SyncFinishOutcome
    data object Timeout : SyncFinishOutcome
}

/**
 * Indirection so a caller can wait for Gadgetbridge's ACTIVITY_SYNC_FINISH broadcast
 * without depending on Android's BroadcastReceiver machinery directly. Used only from the
 * home screen's pull-to-refresh, which is a deliberate, user-watched gesture and can
 * therefore afford to wait for the Bluetooth round trip to actually finish before asking
 * Gadgetbridge to export -- unlike [Ingestor]'s periodic pass, which fires the sync request
 * and moves straight on (see its kdoc for why).
 */
interface SyncSignal {

    /** Same contract as [ExportSignal.awaitExport], for the sync-finish broadcast instead. */
    suspend fun awaitSyncFinish(timeoutMillis: Long, trigger: () -> Unit): SyncFinishOutcome
}

/** Listens for Gadgetbridge's ACTIVITY_SYNC_FINISH broadcast. See [awaitBroadcast]. */
class BroadcastSyncSignal(private val context: Context) : SyncSignal {

    override suspend fun awaitSyncFinish(timeoutMillis: Long, trigger: () -> Unit): SyncFinishOutcome =
        awaitBroadcast(
            context = context,
            actions = listOf(GadgetbridgeCommands.ACTION_ACTIVITY_SYNC_FINISH),
            timeoutMillis = timeoutMillis,
            onTimeout = SyncFinishOutcome.Timeout,
            trigger = trigger,
            onAction = { action ->
                if (action == GadgetbridgeCommands.ACTION_ACTIVITY_SYNC_FINISH) SyncFinishOutcome.Finished else null
            },
        )
}
