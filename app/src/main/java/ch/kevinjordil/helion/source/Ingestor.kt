package ch.kevinjordil.helion.source

import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.store.SyncState
import kotlinx.coroutines.CancellationException

sealed interface IngestResult {
    /** No export file has been chosen yet. */
    data object NoSource : IngestResult
    data class Ingested(val minutes: Int, val points: Int) : IngestResult
    data class Failed(val reason: String) : IngestResult
}

/**
 * One ingestion pass: ask Gadgetbridge to refresh and export, wait for the export to
 * actually finish, read what is newer than the watermarks, and store it.
 *
 * The watermarks are derived from Helion's own archive, one per series (see [Watermarks]),
 * rather than kept as a separate counter. Nothing needs to be written back after a pass:
 * storing a sample *is* moving that series' watermark, so a partial or unreadable export,
 * a failed export, or a timeout waiting for one, all leave the next pass retrying exactly
 * the range that is still missing. Every write is idempotent on a timestamp-keyed row, so
 * re-reading a range that was already stored is free of consequence.
 */
class Ingestor(
    private val reader: ExportReader,
    private val commands: GadgetbridgeCommands,
    private val signal: ExportSignal,
    private val db: HelionDatabase,
    private val now: () -> Long,
) {

    suspend fun ingest(databasePath: String?): IngestResult {
        if (databasePath == null) return IngestResult.NoSource

        // Gadgetbridge also broadcasts ACTIVITY_SYNC_FINISH, but this pass does not wait
        // for it before requesting the export. ACTIVITY_SYNC round-trips to the device
        // over Bluetooth and is not guaranteed to finish (or to broadcast completion) in
        // a bounded time if the device is unreachable, whereas TRIGGER_DATABASE_EXPORT
        // just dumps whatever Gadgetbridge already has locally. The sync is requested as
        // a best-effort refresh; anything it captures too late for this export is picked
        // up by the next periodic pass.
        //
        // Waiting on the export broadcast bounds the wait and proves that *an* export
        // completed recently -- it does NOT prove the export matches this request. The
        // Intent API carries no correlation id, the filter matches on action only, and
        // Gadgetbridge fires the identical broadcast for its own scheduled auto-export,
        // so a concurrent auto-export that started before our trigger() can satisfy this
        // wait. Building a correlation mechanism was considered and rejected: the Intent
        // API exposes none, so we would be inventing a protocol Gadgetbridge does not
        // speak. Both ways this can go wrong fail safe: the subsequent read is either
        // stale (nothing new -> Ingested(0, 0), retried next pass) or partial (throws ->
        // Failed, nothing stored). Registration is also necessarily
        // RECEIVER_EXPORTED for cross-app delivery (see BroadcastExportSignal), so in
        // principle another app could spoof the success broadcast too -- same bounded
        // harm applies.
        commands.requestSync()
        val outcome = signal.awaitExport(EXPORT_TIMEOUT_MILLIS) { commands.requestExport() }

        return when (outcome) {
            ExportOutcome.Failure -> fail("Gadgetbridge reported that the export failed")
            ExportOutcome.Timeout -> fail("Timed out waiting for Gadgetbridge to export")
            ExportOutcome.Success -> readAndStore(databasePath)
        }
    }

    /**
     * The watermark of every series Helion stores, read back out of the archive itself.
     * A series that has never been stored gets 0 and is backfilled in full.
     */
    private suspend fun watermarks(): Watermarks = Watermarks(
        minutes = db.minuteSamples().latestTimestamp() ?: 0,
        points = ExportReader.POINT_SERIES_NAMES.associateWith { series ->
            db.pointSamples().latest(series)?.timestamp ?: 0
        },
    )

    private suspend fun readAndStore(databasePath: String): IngestResult = try {
        val samples = reader.read(databasePath, watermarks())
        db.minuteSamples().upsertAll(samples.minutes)
        db.pointSamples().upsertAll(samples.points)

        db.syncState().put(SyncState(lastSyncAttempt = now(), lastError = null))
        IngestResult.Ingested(samples.minutes.size, samples.points.size)
    } catch (e: CancellationException) {
        // A cooperative stop (e.g. WorkManager tearing down the worker mid-pass) is not
        // a Gadgetbridge failure: rethrow so the coroutine actually cancels instead of
        // being reinterpreted as Failed and issuing a doomed write on a dead job.
        throw e
    } catch (e: Exception) {
        fail(e.message ?: e::class.simpleName.orEmpty())
    }

    private suspend fun fail(reason: String): IngestResult.Failed {
        db.syncState().put(SyncState(lastSyncAttempt = now(), lastError = reason))
        return IngestResult.Failed(reason)
    }

    companion object {
        // The export is a local dump of Gadgetbridge's own database, not a Bluetooth
        // round trip, so it is expected to finish in well under a second even on a
        // large history. 30 seconds leaves generous headroom for a slow device or a
        // busy phone while still failing fast enough, relative to a periodic worker
        // that runs every 30 minutes, for the next scheduled pass to retry promptly
        // instead of the wait becoming the bottleneck.
        const val EXPORT_TIMEOUT_MILLIS = 30_000L
    }
}
