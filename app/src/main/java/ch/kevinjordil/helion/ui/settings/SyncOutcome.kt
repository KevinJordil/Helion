package ch.kevinjordil.helion.ui.settings

import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.source.IngestResult

/**
 * Result of one "Sync now" tap, already resolved down to what the user needs to be told.
 * Deliberately distinguishes [NotConfigured] from [Unavailable]: the former means nothing
 * has been chosen yet, the latter means something was chosen but can no longer be read
 * (moved, deleted, or the permission was lost) -- see
 * [ch.kevinjordil.helion.source.ExportUnavailableException]. Those
 * two need different messages, so this type keeps a caller from collapsing them back into
 * one "nothing happened" case.
 */
sealed interface SyncOutcome {
    data object NotConfigured : SyncOutcome
    data class Unavailable(val reason: String) : SyncOutcome

    /** See [IngestResult.Ingested.refreshTriggered]: false means this is stale-but-good data. */
    data class Ingested(val minutes: Int, val points: Int, val refreshTriggered: Boolean = true) : SyncOutcome
    data class Failed(val reason: String) : SyncOutcome
}

/**
 * Runs one ingestion pass and resolves it to a [SyncOutcome]. Takes [ingest] as a plain
 * function -- not [ch.kevinjordil.helion.AppContainer] directly -- so this stays testable with
 * fakes and does not need Robolectric or a real database. The export is copied inside the
 * pass, after Gadgetbridge has written it (see [ch.kevinjordil.helion.source.Ingestor]), so an
 * unreadable file comes back as [IngestResult.Unavailable] rather than as an exception.
 */
suspend fun runSync(ingest: suspend () -> IngestResult): SyncOutcome =
    when (val result = ingest()) {
        IngestResult.NoSource -> SyncOutcome.NotConfigured
        is IngestResult.Unavailable -> SyncOutcome.Unavailable(result.reason)
        is IngestResult.Ingested -> SyncOutcome.Ingested(result.minutes, result.points, result.refreshTriggered)
        is IngestResult.Failed -> SyncOutcome.Failed(result.reason)
    }

/**
 * Maps a [SyncOutcome] to a string resource and its format arguments, ready for
 * `stringResource(resId, *args)`. Kept separate from [runSync] and free of any Context so
 * the mapping itself -- which message for which outcome -- is testable as a plain function.
 */
fun syncMessage(outcome: SyncOutcome): Pair<Int, List<Any>> = when (outcome) {
    is SyncOutcome.NotConfigured -> R.string.sync_result_not_configured to emptyList()
    is SyncOutcome.Unavailable -> R.string.sync_result_unavailable to emptyList()
    is SyncOutcome.Ingested -> if (outcome.refreshTriggered) {
        R.string.sync_result_success to listOf(outcome.minutes, outcome.points)
    } else {
        // Gadgetbridge could not be triggered this pass -- this is whatever it last wrote
        // on its own schedule, not confirmed fresh. A working, less-fresh state, said
        // outright rather than presented as if a refresh had just happened.
        R.string.sync_result_success_stale to listOf(outcome.minutes, outcome.points)
    }
    is SyncOutcome.Failed -> R.string.sync_result_failed to listOf(outcome.reason)
}
