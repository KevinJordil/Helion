package ch.kevinjordil.helion.source

import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.store.SyncState

sealed interface IngestResult {
    /** No export file has been chosen yet. */
    data object NoSource : IngestResult
    data class Ingested(val minutes: Int, val points: Int) : IngestResult
    data class Failed(val reason: String) : IngestResult
}

/**
 * One ingestion pass: ask Gadgetbridge to refresh and export, wait for the export to
 * actually finish, read what is newer than the watermark, store it, then move the
 * watermark.
 *
 * The watermark only moves after a complete pass, and never backwards -- see
 * [SyncState.lastIngestedTimestamp]. A partial or unreadable export, a failed export,
 * or a timeout waiting for one, all leave it untouched so the next pass retries the
 * same range rather than silently skipping data.
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
        // just dumps whatever Gadgetbridge already has locally. Waiting on the export
        // broadcast alone is enough to know the exported file matches this request, and
        // avoids stacking a second bounded wait -- and a second failure mode -- ahead of
        // it. The sync is requested as a best-effort refresh; anything it captures too
        // late for this export is picked up by the next periodic pass.
        commands.requestSync()
        val outcome = signal.awaitExport(EXPORT_TIMEOUT_MILLIS) { commands.requestExport() }

        val previous = db.syncState().get()
        val since = previous?.lastIngestedTimestamp ?: 0

        return when (outcome) {
            ExportOutcome.Failure -> fail(since, "Gadgetbridge reported that the export failed")
            ExportOutcome.Timeout -> fail(since, "Timed out waiting for Gadgetbridge to export")
            ExportOutcome.Success -> readAndStore(databasePath, since)
        }
    }

    private suspend fun readAndStore(databasePath: String, since: Long): IngestResult = try {
        val samples = reader.read(databasePath, since)
        db.minuteSamples().upsertAll(samples.minutes)
        db.pointSamples().upsertAll(samples.points)

        val highestRead = (samples.minutes.map { it.timestamp } + samples.points.map { it.timestamp })
            .maxOrNull()

        // The watermark only ever moves forward: whatever was read this pass can never
        // push it below where it already was, no matter what timestamps come back.
        val watermark = maxOf(since, highestRead ?: since)

        db.syncState().put(
            SyncState(
                lastIngestedTimestamp = watermark,
                lastSyncAttempt = now(),
                lastError = null,
            ),
        )
        IngestResult.Ingested(samples.minutes.size, samples.points.size)
    } catch (e: Exception) {
        fail(since, e.message ?: e::class.simpleName.orEmpty())
    }

    private suspend fun fail(since: Long, reason: String): IngestResult.Failed {
        db.syncState().put(
            SyncState(
                lastIngestedTimestamp = since,
                lastSyncAttempt = now(),
                lastError = reason,
            ),
        )
        return IngestResult.Failed(reason)
    }

    companion object {
        // The export is a local dump of Gadgetbridge's own database, not a Bluetooth
        // round trip, so it is expected to finish in well under a second even on a
        // large history. 30 seconds leaves generous headroom for a slow device or a
        // busy phone while still failing fast enough, relative to a periodic worker
        // that runs every few minutes, for the next scheduled pass to retry promptly
        // instead of the wait becoming the bottleneck.
        const val EXPORT_TIMEOUT_MILLIS = 30_000L
    }
}
