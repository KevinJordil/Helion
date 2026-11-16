package ch.kevinjordil.helion.ui.home

import ch.kevinjordil.helion.source.IngestResult
import ch.kevinjordil.helion.ui.settings.SyncOutcome
import ch.kevinjordil.helion.ui.settings.runSync

/**
 * Where a foreground sync is, moment to moment, so the screen can show real progress
 * instead of a plain spinner.
 */
enum class RefreshPhase {
    /** Waiting for Gadgetbridge's Bluetooth sync to finish. */
    SYNCING,

    /** Sync done (or timed out) -- triggering the export and reading it into the store. */
    READING,

    /** The pass is over, successfully or not. */
    DONE,
}

/**
 * One foreground sync pass (app opened, pull-to-refresh, Réglages' "Synchroniser"): waits for
 * Gadgetbridge's Bluetooth sync to actually land before the export is triggered -- otherwise
 * the export can race the sync and capture data from before it -- reporting each phase so the
 * screen can show real progress. [awaitSyncFinish] is expected to be
 * [ch.kevinjordil.helion.source.BroadcastSyncSignal.awaitSyncFinish], already bounded by its
 * own timeout: whether it finishes or times out, this pass moves on and reads whatever is on
 * disk. A timed-out sync is not reported as an error on its own;
 * [IngestResult.Ingested.refreshTriggered] (surfaced through [SyncOutcome] by [runSync])
 * already tells the screen whether what came back was confirmed fresh.
 *
 * [ingest] is expected to call [ch.kevinjordil.helion.source.Ingestor.ingest] with
 * `skipSyncRequest = true`, since the sync was requested and awaited here already.
 */
suspend fun performRefresh(
    onPhase: (RefreshPhase) -> Unit,
    requestSync: () -> Unit,
    awaitSyncFinish: suspend (trigger: () -> Unit) -> Unit,
    ingest: suspend () -> IngestResult,
): SyncOutcome {
    onPhase(RefreshPhase.SYNCING)
    awaitSyncFinish { requestSync() }
    onPhase(RefreshPhase.READING)
    val outcome = runSync(ingest)
    onPhase(RefreshPhase.DONE)
    return outcome
}
