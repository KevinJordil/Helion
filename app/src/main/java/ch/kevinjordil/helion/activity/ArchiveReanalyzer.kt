package ch.kevinjordil.helion.activity

import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.store.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** What one call to [ArchiveReanalyzer.reanalyze] found, or why it did nothing. */
sealed interface ReanalysisOutcome {
    /** [candidatesCreated] can legitimately be zero -- an untouched range with nothing new in it. */
    data class Completed(val candidatesCreated: Int) : ReanalysisOutcome

    /** A previous call is still running -- see [ArchiveReanalyzer]'s own kdoc. */
    data object AlreadyRunning : ReanalysisOutcome

    /** The archive has no minute samples at all yet: nothing for detection to look at. */
    data object NothingStored : ReanalysisOutcome
}

/**
 * The one entry point for re-running [ActivityDetector] over everything in the archive,
 * not just the day-deep lookback [ch.kevinjordil.helion.source.Ingestor] applies around an
 * ingest pass's own new minutes. It exists for exactly one reason: a threshold change (see
 * [DetectionThresholds]'s own kdoc) only ever gets applied, going forward, to data an
 * ordinary pass happens to touch again -- everything older is simply never re-examined
 * unless something calls [detect] over it directly, which is all this class does.
 *
 * **Never proposes over what is already decided.** This class does not duplicate
 * [ActivityDao.overlapping]'s dedup logic -- it does not need to, because [ActivityDetector.detect]
 * already runs it before every single insert, for every pass, over every range this class
 * ever hands it. A confirmed, published or dismissed range, and any manually created or
 * hand-edited activity, is exactly as untouchable here as it is on an ordinary ingest pass.
 *
 * **Bounded memory, not one giant query.** The archive is read in [CHUNK_SECONDS]-wide
 * slices rather than `detect(earliest, now)` in one call: [ActivityDetector.detect] loads
 * every minute sample in its own window into memory at once (for pass 2's free-session
 * scan), and an unbounded call would mean loading the entire archive -- already tens of
 * thousands of rows, growing daily -- in one shot. Two weeks keeps each slice's own load
 * small (on the order of twenty thousand minute rows at most) while staying comfortably
 * larger than any real session or slot occurrence this app has ever seen. Consecutive
 * slices overlap by [OVERLAP_SECONDS] -- the same day of look-back
 * [ch.kevinjordil.helion.source.Ingestor.DETECTION_LOOKBACK_BUFFER_SECONDS] uses -- so a
 * session that happens to straddle a slice boundary is still looked at whole by the slice
 * that looks back over it, rather than being cut in two and missed by both. The overlap
 * costs nothing beyond the re-check itself: [ActivityDao.overlapping] makes re-examining a
 * range already decided a cheap no-op.
 *
 * **Cancellable, and safe to call twice.** The whole slice loop runs under
 * `withContext(Dispatchers.Default)`, off the caller's own thread, so a caller-side
 * cancellation (the owner navigating away, or an explicit cancel) is honoured at the next
 * slice boundary the same way any other cooperative coroutine cancellation is. [runLock]
 * refuses a second, concurrent call outright ([ReanalysisOutcome.AlreadyRunning]) rather
 * than letting two passes race the same slices; a second call made *after* the first
 * finished is just as safe, since it re-derives every candidate through the same
 * overlap-checked insert path and creates nothing where the first call (or the owner's own
 * review since) already settled the matter.
 */
class ArchiveReanalyzer(
    private val db: HelionDatabase,
    private val detector: ActivityDetector,
    private val now: () -> Long,
) {
    private val runLock = Mutex()

    suspend fun reanalyze(): ReanalysisOutcome {
        if (!runLock.tryLock()) return ReanalysisOutcome.AlreadyRunning
        try {
            return withContext(Dispatchers.Default) {
                val earliest = db.minuteSamples().earliestTimestamp() ?: return@withContext ReanalysisOutcome.NothingStored
                val latest = now()

                var created = 0
                var sliceEnd = earliest
                while (sliceEnd < latest) {
                    val sliceStart = sliceEnd
                    sliceEnd = (sliceStart + CHUNK_SECONDS).coerceAtMost(latest)
                    created += detector.detect((sliceStart - OVERLAP_SECONDS).coerceAtLeast(earliest), sliceEnd)
                }

                recordCompletion()
                ReanalysisOutcome.Completed(created)
            }
        } finally {
            runLock.unlock()
        }
    }

    private suspend fun recordCompletion() {
        val existing = db.syncState().get()
        db.syncState().put(
            (existing ?: SyncState(lastSyncAttempt = 0, lastError = null)).copy(lastFullDetectionRun = now()),
        )
    }

    companion object {
        /** See this class' own kdoc for why fourteen days. */
        const val CHUNK_SECONDS = 14L * 24 * 60 * 60

        /** Matches [ch.kevinjordil.helion.source.Ingestor.DETECTION_LOOKBACK_BUFFER_SECONDS]. */
        const val OVERLAP_SECONDS = 24L * 60 * 60
    }
}
