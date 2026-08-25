package ch.kevinjordil.helion.ui.home

import ch.kevinjordil.helion.source.IngestResult
import ch.kevinjordil.helion.ui.settings.SyncOutcome
import ch.kevinjordil.helion.ui.settings.runSync

/**
 * Where pull-to-refresh is, moment to moment, so the screen can show real progress
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
 * One pull-to-refresh pass: unlike [ch.kevinjordil.helion.source.Ingestor]'s periodic
 * pass, this is a gesture with him watching, so it is worth waiting for the Bluetooth
 * sync to actually land before asking Gadgetbridge to export -- otherwise the export can
 * race the sync and capture data from before it. [awaitSyncFinish] is expected to be
 * [ch.kevinjordil.helion.source.BroadcastSyncSignal.awaitSyncFinish], already bounded by
 * its own timeout: whether it finishes or times out, this pass moves on regardless and
 * reads whatever is on disk, exactly as [ch.kevinjordil.helion.source.Ingestor] already
 * does for the export broadcast. A timed-out sync is not reported as an error on its own;
 * [IngestResult.Ingested.refreshTriggered] (surfaced through [SyncOutcome] by [runSync])
 * already tells the screen whether what came back was confirmed fresh.
 *
 * [copyToCache] and [ingest] are plain suspend/plain functions, not
 * [ch.kevinjordil.helion.AppContainer] directly, so this stays testable with fakes -- same
 * shape as [runSync], which this delegates the outcome mapping to rather than duplicating it.
 */
suspend fun performRefresh(
    onPhase: (RefreshPhase) -> Unit,
    requestSync: () -> Unit,
    awaitSyncFinish: suspend (trigger: () -> Unit) -> Unit,
    copyToCache: () -> String?,
    ingest: suspend (String?) -> IngestResult,
): SyncOutcome {
    onPhase(RefreshPhase.SYNCING)
    awaitSyncFinish { requestSync() }
    onPhase(RefreshPhase.READING)
    val outcome = runSync(copyToCache, ingest)
    onPhase(RefreshPhase.DONE)
    return outcome
}
