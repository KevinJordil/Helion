package ch.kevinjordil.helion.source

import ch.kevinjordil.helion.activity.ActivityDetector
import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.store.SyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface IngestResult {
    /** No export file has been chosen yet. */
    data object NoSource : IngestResult

    /**
     * [refreshTriggered] is true only when Gadgetbridge actually confirmed a fresh export
     * for this pass. When false, [minutes] and [points] were read from whatever export file
     * was already on disk -- e.g. Gadgetbridge's own scheduled auto-export -- because
     * triggering one was not attempted (backed off, see [Ingestor]) or did not succeed
     * (failed or timed out). The data itself is not degraded or suspect; only its freshness
     * is unconfirmed, and callers must say so rather than imply a refresh just happened.
     */
    data class Ingested(
        val minutes: Int,
        val points: Int,
        val refreshTriggered: Boolean = true,
        val stageSegments: Int = 0,
    ) : IngestResult

    data class Failed(val reason: String) : IngestResult
}

/**
 * One ingestion pass: try to ask Gadgetbridge to refresh and export, then -- regardless of
 * whether that trigger succeeded, failed, or timed out -- read whatever export file is
 * actually on disk and store what is newer than the watermarks.
 *
 * Triggering a fresh export is a freshness optimisation, not a precondition for reading:
 * Gadgetbridge also exports on its own schedule, so a file worth reading can exist even when
 * Helion never managed to command a refresh (e.g. a Gadgetbridge build that only exposes the
 * per-device Bluetooth Intent API, not the general one this app drives). Only a file that
 * cannot be read, or an ingestion that genuinely fails, is reported as [IngestResult.Failed].
 *
 * The watermarks are derived from Helion's own archive, one per series (see [Watermarks]),
 * rather than kept as a separate counter. Nothing needs to be written back after a pass:
 * storing a sample *is* moving that series' watermark, so a partial or unreadable export
 * leaves the next pass retrying exactly the range that is still missing. Every write is
 * idempotent on a timestamp-keyed row, so re-reading a range that was already stored is free
 * of consequence.
 *
 * Passes are serialised. The periodic worker and a manual "Sync now" tap can be triggered
 * at the same moment -- most likely precisely when the data looks stale -- and two
 * overlapping passes would each register a receiver for the same broadcast, each trigger
 * an export, and each read a cache file the other one is replacing. They queue instead.
 *
 * Trigger backoff: a phone whose Gadgetbridge cannot be triggered would otherwise pay the
 * full 30 s wait every periodic pass, forever, for a trigger that can never succeed. After
 * [TRIGGER_FAILURE_THRESHOLD] consecutive failed or timed-out attempts, periodic passes stop
 * attempting to trigger and go straight to reading the existing file, until
 * [TRIGGER_RETRY_INTERVAL_SECONDS] has passed since the last attempt -- long enough that a
 * Gadgetbridge update or the user enabling the Intent API is noticed within a day, without
 * spending a wake-lock on it every half hour. A manual "Sync now" tap (`force = true`) always
 * attempts to trigger regardless of this backoff: it is a deliberate, user-initiated wait,
 * and it doubles as the immediate way to find out the moment triggering starts working again.
 *
 * [ingest]'s `skipSyncRequest` exists for exactly one caller: Accueil's pull-to-refresh and
 * open-sync, which already drive and await Gadgetbridge's own ACTIVITY_SYNC_FINISH broadcast
 * themselves (see [ch.kevinjordil.helion.ui.home.performRefresh]) before ever calling
 * [ingest]. Without it, this pass would call [GadgetbridgeCommands.requestSync] a second
 * time right as the device's Bluetooth sync had just finished -- harmless (a plain
 * broadcast, not a stateful call) but a needless second round-trip on every such refresh.
 * The periodic worker and a plain manual "Sync now" (Réglages) never set it: they have not
 * pre-waited for a sync, so they still need this pass to request one itself.
 */
class Ingestor(
    private val reader: ExportReader,
    private val commands: GadgetbridgeCommands,
    private val signal: ExportSignal,
    private val db: HelionDatabase,
    private val now: () -> Long,
) {

    private val passLock = Mutex()

    /**
     * Set by [ch.kevinjordil.helion.AppContainer] after construction, not taken as a
     * constructor parameter: a constructor slot here would sit before the trailing-lambda
     * [now] parameter every existing caller (including every test) already relies on,
     * forcing every one of them into named-argument syntax for no benefit. Left null, a
     * pass simply stores whatever it read without attempting detection -- the safe default
     * for, say, a test that has no interest in activity detection at all.
     */
    var detector: ActivityDetector? = null

    suspend fun ingest(databasePath: String?, force: Boolean = false, skipSyncRequest: Boolean = false): IngestResult {
        if (databasePath == null) return IngestResult.NoSource
        return passLock.withLock { runPass(databasePath, force, skipSyncRequest) }
    }

    private suspend fun runPass(databasePath: String, force: Boolean, skipSyncRequest: Boolean): IngestResult {
        val state = db.syncState().get()
        val streak = state?.triggerFailureStreak ?: 0
        val lastAttempt = state?.lastTriggerAttempt ?: 0
        val nowSeconds = now()
        val shouldAttempt = force ||
            streak < TRIGGER_FAILURE_THRESHOLD ||
            (nowSeconds - lastAttempt) >= TRIGGER_RETRY_INTERVAL_SECONDS

        var triggered = false
        var newStreak = streak
        var newLastAttempt = lastAttempt
        if (shouldAttempt) {
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
            //
            // skipSyncRequest omits only this requestSync() call, not the export trigger
            // below: the caller pre-waited for the sync itself, but Ingestor still owns
            // triggering and awaiting the export.
            if (!skipSyncRequest) commands.requestSync()
            val outcome = signal.awaitExport(EXPORT_TIMEOUT_MILLIS) { commands.requestExport() }
            newLastAttempt = nowSeconds
            when (outcome) {
                ExportOutcome.Success -> {
                    triggered = true
                    newStreak = 0
                }
                ExportOutcome.Failure, ExportOutcome.Timeout -> {
                    newStreak = streak + 1
                }
            }
        }

        // A timeout or a reported failure no longer short-circuits the read: whether or not
        // triggering worked, whatever export file is already on disk is read and ingested.
        return readAndStore(databasePath, triggered, newStreak, newLastAttempt)
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
        sessions = db.sleepStageSegments().latestSessionEnd() ?: 0,
    )

    private suspend fun readAndStore(
        databasePath: String,
        triggered: Boolean,
        streak: Int,
        lastAttempt: Long,
    ): IngestResult = try {
        val samples = reader.read(databasePath, watermarks())
        db.minuteSamples().upsertAll(samples.minutes)
        db.pointSamples().upsertAll(samples.points)
        db.sleepStageSegments().upsertAll(samples.stageSegments)

        db.syncState().put(
            SyncState(
                lastSyncAttempt = now(),
                lastError = null,
                triggerFailureStreak = streak,
                lastTriggerAttempt = lastAttempt,
            ),
        )
        runDetectionOver(samples.minutes)
        IngestResult.Ingested(samples.minutes.size, samples.points.size, triggered, samples.stageSegments.size)
    } catch (e: CancellationException) {
        // A cooperative stop (e.g. WorkManager tearing down the worker mid-pass) is not
        // a Gadgetbridge failure: rethrow so the coroutine actually cancels instead of
        // being reinterpreted as Failed and issuing a doomed write on a dead job.
        throw e
    } catch (e: Exception) {
        fail(e.message ?: e::class.simpleName.orEmpty(), streak, lastAttempt)
    }

    /**
     * Runs [detector] (when wired) over the span this pass just stored new minute samples
     * for, plus a day of lookback so a slot occurrence or a free session that started
     * before this pass' own earliest new minute is still resolved against its full span
     * rather than a truncated one. Detection failing must never turn a genuinely successful
     * ingest pass into a reported failure -- new heart-rate and movement data is real and
     * worth keeping either way -- so any exception from it is swallowed here, not
     * propagated.
     */
    private suspend fun runDetectionOver(newMinutes: List<MinuteSample>) {
        val detector = detector ?: return
        val earliest = newMinutes.minOfOrNull { it.timestamp } ?: return
        val latest = newMinutes.maxOf { it.timestamp } + 60
        try {
            detector.detect((earliest - DETECTION_LOOKBACK_BUFFER_SECONDS).coerceAtLeast(0), latest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best-effort: a detection bug must not take down ingestion itself.
        }
    }

    private suspend fun fail(reason: String, streak: Int, lastAttempt: Long): IngestResult.Failed {
        db.syncState().put(
            SyncState(
                lastSyncAttempt = now(),
                lastError = reason,
                triggerFailureStreak = streak,
                lastTriggerAttempt = lastAttempt,
            ),
        )
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

        /**
         * Consecutive failed-or-timed-out trigger attempts before a periodic pass stops
         * attempting and goes straight to reading the existing file. Three attempts is
         * enough to rule out a one-off blip (Bluetooth busy, phone briefly asleep) without
         * dragging out the futile wait for a Gadgetbridge build that simply does not expose
         * the Intent API.
         */
        const val TRIGGER_FAILURE_THRESHOLD = 3

        /**
         * Once backed off, how long a periodic pass waits before trying to trigger again,
         * in seconds (matches [now]'s unit). A day is often enough to notice a Gadgetbridge
         * update or the user flipping the setting, without spending a 30 s wake-lock on it
         * every half hour in between. A manual "Sync now" tap always attempts regardless.
         */
        const val TRIGGER_RETRY_INTERVAL_SECONDS = 24 * 60 * 60L

        /**
         * How far before the earliest newly stored minute [runDetectionOver] widens the
         * detection window, so a slot occurrence or a free session that started the day
         * before this pass' own new data still resolves against its complete span rather
         * than one truncated at this pass' own boundary. Re-examining a day already fully
         * decided costs nothing extra: [ActivityDao.overlapping] makes every re-check a
         * cheap no-op wherever the archive already has an answer.
         */
        const val DETECTION_LOOKBACK_BUFFER_SECONDS = 24 * 60 * 60L
    }
}
