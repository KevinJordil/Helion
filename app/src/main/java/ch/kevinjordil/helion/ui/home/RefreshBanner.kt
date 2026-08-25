package ch.kevinjordil.helion.ui.home

import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.ui.settings.SyncOutcome
import ch.kevinjordil.helion.ui.settings.syncMessage

/**
 * A pull-to-refresh outcome, resolved down to what Accueil actually renders: a string
 * resource with its format args, and whether it earns the one colour reserved for "this
 * needs attention" -- amber must never be decorative, so only a genuine problem
 * ([SyncOutcome.Failed], [SyncOutcome.Unavailable]) sets [isAttention]. A degraded-but-working
 * pass ([SyncOutcome.Ingested] with `refreshTriggered == false`) is stated plainly, in the
 * neutral colour, exactly per the design brief: "a degraded-yet-working sync is not an
 * error and must not look alarming."
 *
 * [SyncOutcome.NotConfigured] has no banner here: Accueil never reaches a refresh in that
 * state, since [HomeStatus.NoSource] replaces the whole screen with the "go to Réglages"
 * empty state before a refresh is even offered.
 */
data class RefreshBanner(val messageRes: Int, val args: List<Any>, val isAttention: Boolean)

fun refreshBanner(outcome: SyncOutcome): RefreshBanner {
    val (messageRes, args) = syncMessage(outcome)
    val isAttention = outcome is SyncOutcome.Failed || outcome is SyncOutcome.Unavailable
    return RefreshBanner(messageRes, args, isAttention)
}

/** String resource for the given [RefreshPhase], while a refresh is in progress. */
fun refreshPhaseLabel(phase: RefreshPhase): Int? = when (phase) {
    RefreshPhase.SYNCING -> R.string.refresh_phase_syncing
    RefreshPhase.READING -> R.string.refresh_phase_reading
    RefreshPhase.DONE -> null
}
