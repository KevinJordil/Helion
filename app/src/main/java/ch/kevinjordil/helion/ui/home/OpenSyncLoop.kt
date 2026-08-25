package ch.kevinjordil.helion.ui.home

import ch.kevinjordil.helion.ui.settings.SyncOutcome

/**
 * Repeats [runPass] (one [performRefresh] pass) after app open until the archive is
 * genuinely current, instead of firing a single pass and calling it done.
 *
 * "Genuinely current" is deliberately NOT "0 minutes old": the strap samples once a
 * minute, so the newest sample is always at least a little old, and chasing "0 min" would
 * loop forever and drain the battery for a target that can never be reached. The real
 * signal that nothing is left to fetch is a pass that ingests nothing new -- see
 * [hasMoreToIngest] -- so that is the stop condition: keep going while a pass is still
 * finding new minutes or points, stop the moment one finds none.
 *
 * A pass that is [SyncOutcome.Failed], [SyncOutcome.NotConfigured] or
 * [SyncOutcome.Unavailable] also stops the loop immediately rather than being retried here:
 * none of those states will change by simply asking again a few seconds later (nothing is
 * configured, the export cannot be read, or the pass errored for a reason retrying blindly
 * will not fix), and the existing freshness/amber line already tells that story honestly --
 * see [ch.kevinjordil.helion.ui.home.HomeScreen]. Retrying that case is still what the
 * *next* debounced open-sync (via [OpenSyncGate]) or the periodic worker is for.
 *
 * Both caps in [OpenSyncLoopLimits] are hard stops, whichever is hit first: if the loop
 * still has more to ingest when it stops, that is left as-is for the freshness line to
 * report truthfully rather than retried silently forever.
 */
suspend fun runOpenSyncLoop(
    maxPasses: Int = OpenSyncLoopLimits.MAX_PASSES,
    passDelayMillis: Long = OpenSyncLoopLimits.PASS_DELAY_MILLIS,
    timeBudgetMillis: Long = OpenSyncLoopLimits.TIME_BUDGET_MILLIS,
    elapsedMillis: () -> Long = System::currentTimeMillis,
    delay: suspend (Long) -> Unit = { millis -> kotlinx.coroutines.delay(millis) },
    runPass: suspend () -> SyncOutcome,
    onPass: suspend (SyncOutcome) -> Unit = {},
) {
    val startedAt = elapsedMillis()
    var passes = 0
    while (true) {
        val outcome = runPass()
        passes += 1
        onPass(outcome)

        if (!hasMoreToIngest(outcome)) break
        if (passes >= maxPasses) break
        if (elapsedMillis() - startedAt >= timeBudgetMillis) break

        delay(passDelayMillis)
    }
}

/** True only for the one outcome that says "there was more, and this pass found it". */
private fun hasMoreToIngest(outcome: SyncOutcome): Boolean =
    outcome is SyncOutcome.Ingested && (outcome.minutes > 0 || outcome.points > 0)

object OpenSyncLoopLimits {
    /**
     * At most four passes per app open (the first attempt plus up to three follow-ups).
     * Bounded low on purpose: this loop exists to catch up after a normal gap since the
     * last sync, not to hammer Gadgetbridge indefinitely on a phone that genuinely has
     * nothing more to give (Bluetooth out of range, device off-wrist). Combined with
     * [PASS_DELAY_MILLIS], four passes is worst case a few minutes of background work --
     * see [TIME_BUDGET_MILLIS] for the independent hard stop on that.
     */
    const val MAX_PASSES = 4

    /**
     * Ninety seconds between passes. The strap records one sample per minute, so back to
     * back passes with no pause would just re-read the same export Gadgetbridge has not
     * had time to update -- wasted work, not progress. Ninety seconds is comfortably more
     * than one sampling interval plus the time Gadgetbridge's own Bluetooth sync typically
     * needs to land a fresh minute before the next pass asks for an export.
     */
    const val PASS_DELAY_MILLIS = 90_000L

    /**
     * Six minutes, independent of [MAX_PASSES]: a second hard stop so a run of slow passes
     * (each already bounded individually, but Bluetooth timeouts stack) cannot keep the
     * loop -- and the wake lock it implies -- running much past what four passes should
     * normally take, protecting battery even if the per-pass caps are all hit at once.
     */
    const val TIME_BUDGET_MILLIS = 360_000L
}
