package ch.kevinjordil.helion.ui.settings

import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.source.ExportUnavailableException
import ch.kevinjordil.helion.source.IngestResult

/**
 * Result of one "Sync now" tap, already resolved down to what the user needs to be told.
 * Deliberately distinguishes [NotConfigured] from [Unavailable]: the former means nothing
 * has been chosen yet, the latter means something was chosen but can no longer be read
 * (moved, deleted, or the permission was lost) -- see [ExportUnavailableException]. Those
 * two need different messages, so this type keeps a caller from collapsing them back into
 * one "nothing happened" case.
 */
sealed interface SyncOutcome {
    data object NotConfigured : SyncOutcome
    data class Unavailable(val reason: String) : SyncOutcome
    data class Ingested(val minutes: Int, val points: Int) : SyncOutcome
    data class Failed(val reason: String) : SyncOutcome
}

/**
 * Runs one ingestion pass and resolves it to a [SyncOutcome]. Takes [copyToCache] and
 * [ingest] as plain functions -- not [ch.kevinjordil.helion.AppContainer] directly -- so
 * this stays testable with fakes and does not need Robolectric or a real database.
 */
suspend fun runSync(
    copyToCache: () -> String?,
    ingest: suspend (String?) -> IngestResult,
): SyncOutcome {
    val path = try {
        copyToCache()
    } catch (e: ExportUnavailableException) {
        return SyncOutcome.Unavailable(e.message.orEmpty())
    }
    return when (val result = ingest(path)) {
        IngestResult.NoSource -> SyncOutcome.NotConfigured
        is IngestResult.Ingested -> SyncOutcome.Ingested(result.minutes, result.points)
        is IngestResult.Failed -> SyncOutcome.Failed(result.reason)
    }
}

/**
 * Maps a [SyncOutcome] to a string resource and its format arguments, ready for
 * `stringResource(resId, *args)`. Kept separate from [runSync] and free of any Context so
 * the mapping itself -- which message for which outcome -- is testable as a plain function.
 */
fun syncMessage(outcome: SyncOutcome): Pair<Int, List<Any>> = when (outcome) {
    is SyncOutcome.NotConfigured -> R.string.sync_result_not_configured to emptyList()
    is SyncOutcome.Unavailable -> R.string.sync_result_unavailable to emptyList()
    is SyncOutcome.Ingested -> R.string.sync_result_success to listOf(outcome.minutes, outcome.points)
    is SyncOutcome.Failed -> R.string.sync_result_failed to listOf(outcome.reason)
}
