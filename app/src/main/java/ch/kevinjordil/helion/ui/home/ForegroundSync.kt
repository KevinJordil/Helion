package ch.kevinjordil.helion.ui.home

import ch.kevinjordil.helion.ui.settings.SyncOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What every screen needs to know about the foreground sync: whether one is running (and in
 * which [RefreshPhase]), how the last one ended, and how many have completed -- a counter a
 * screen keys its reload on, so data shown anywhere refreshes when a pass lands.
 */
data class ForegroundSyncState(
    /** Non-null exactly while a pass is running. */
    val phase: RefreshPhase? = null,
    val lastOutcome: SyncOutcome? = null,
    val completedPasses: Int = 0,
)

/**
 * The single owner of foreground syncs: the one run when the app is opened, pull-to-refresh on
 * Accueil, and Réglages' "Synchroniser maintenant".
 *
 * It lives on [ch.kevinjordil.helion.AppContainer], with an application-wide [scope], rather
 * than inside a screen: the opening sync used to be started by Accueil's composition, so
 * opening the app on any other screen (a notification tap lands straight on an activity)
 * synced nothing, and leaving Accueil cancelled a pass halfway. Started from
 * [ch.kevinjordil.helion.MainActivity.onResume], it now runs wherever the app opens, and
 * finishes even if the user navigates away.
 *
 * One pass at a time: asking for a sync while one is already running returns the running
 * one instead of queueing a second Bluetooth round trip.
 *
 * Independent from the four-hourly background sync ([ch.kevinjordil.helion.source.SyncWorker]):
 * opening the app does not move that schedule.
 */
class ForegroundSync(
    private val scope: CoroutineScope,
    private val gate: OpenSyncGate,
    private val isConfigured: () -> Boolean,
    private val nowSeconds: () -> Long,
    private val runPass: suspend (onPhase: (RefreshPhase) -> Unit) -> SyncOutcome,
) {
    private val mutableState = MutableStateFlow(ForegroundSyncState())
    val state: StateFlow<ForegroundSyncState> = mutableState.asStateFlow()

    private var running: Deferred<SyncOutcome>? = null

    /**
     * Called every time the app comes to the foreground. Starts a pass unless no export file
     * is configured, or [gate] says the last one was too recent (a quick back-and-forth to
     * another app). Returns whether a pass was started.
     */
    fun onAppOpened(): Boolean {
        if (!isConfigured()) return false
        val now = nowSeconds()
        if (!gate.shouldSync(now)) return false
        syncNow()
        return true
    }

    /** Starts a pass, or returns the one already running. Await it for its [SyncOutcome]. */
    @Synchronized
    fun syncNow(): Deferred<SyncOutcome> {
        running?.takeIf { it.isActive }?.let { return it }
        gate.recordAttempt(nowSeconds())
        mutableState.update { it.copy(phase = RefreshPhase.SYNCING) }
        val pass = scope.async {
            try {
                val outcome = try {
                    runPass { phase ->
                        if (phase != RefreshPhase.DONE) mutableState.update { it.copy(phase = phase) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Nobody may be awaiting this pass (the opening sync is fire-and-forget),
                    // so an unexpected failure must become a visible outcome, not vanish.
                    SyncOutcome.Failed(e.message ?: e::class.simpleName.orEmpty())
                }
                mutableState.update { it.copy(phase = null, lastOutcome = outcome, completedPasses = it.completedPasses + 1) }
                outcome
            } finally {
                // Also on cancellation or an unexpected exception: never leave a phase showing
                // for a pass that is no longer running.
                mutableState.update { it.copy(phase = null) }
            }
        }
        running = pass
        return pass
    }
}
