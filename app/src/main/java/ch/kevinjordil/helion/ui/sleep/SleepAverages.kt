package ch.kevinjordil.helion.ui.sleep

import ch.kevinjordil.helion.R

/**
 * A selectable window for Sommeil's averages panel. Four options, not a free-form date
 * range: [LAST_5] mirrors [ch.kevinjordil.helion.ui.quality.MIN_BASELINE_DAYS] -- the
 * shortest stretch this app already considers "enough to say something" elsewhere --
 * [LAST_7] is a calendar week, [LAST_30] is a calendar month and the same span the history
 * list and the personal baseline already use ([SleepReader]'s own [LOOKBACK_DAYS]), and
 * [ALL] is the one window with no fixed size at all, for "how have I actually been
 * sleeping" with nothing left out. Reusing [ch.kevinjordil.helion.ui.metric.MetricScreen]'s
 * own three-option Jour/Semaine/Mois set was considered and rejected: those are calendar
 * buckets meant for a single day's worth of readings, not a count of *nights*, and this
 * screen already counts nights everywhere else (the history list, the baseline).
 *
 * [nightCount] is the number of most recent nights the window covers; `null` for [ALL].
 */
enum class SleepAverageWindow(val nightCount: Int?, val labelRes: Int) {
    LAST_5(5, R.string.sleep_average_window_5),
    LAST_7(7, R.string.sleep_average_window_7),
    LAST_30(30, R.string.sleep_average_window_30),
    ALL(null, R.string.sleep_average_window_all),
}

/**
 * Averages over a window of recent nights, plus exactly how many nights each figure is
 * actually based on -- never left implicit, since "the last 30 nights" and "the four
 * nights this archive actually has" are not the same claim.
 *
 * [totalNights] is how many night episodes [computeSleepAverages] actually found inside
 * the requested window, which can be smaller than the window's own nominal size (e.g.
 * [SleepAverageWindow.LAST_30] against an archive with only four recorded nights).
 *
 * [consideredNights] is, of those, how many are trustworthy enough to average at all: an
 * in-progress night's duration is not yet final ([SleepEpisode.isInProgress]), and a night
 * with [SleepEpisode.hasDataGap] cannot be trusted either -- both are excluded from every
 * figure below rather than zero-filled, exactly the same gate [SleepScreen] already
 * applies before showing a single night's own quality comparisons. An incomplete night is
 * simply not counted, so it can never read as a short night it never actually was.
 *
 * [stageNights] is smaller still: of [consideredNights], only those whose stage track
 * actually resolves to something ([SleepPhaseSource.Measured] or
 * [SleepPhaseSource.Estimated] -- see [resolveSleepPhases]) contribute to
 * [avgDeepMinutes]/[avgRemMinutes]/[avgLightMinutes]. A [SleepPhaseSource.NotEstimable]
 * night is excluded from the stage averages' numerator *and* denominator, never treated as
 * a zero-minute night in every stage -- that would silently drag every stage average down
 * exactly the way this feature was asked not to.
 *
 * [respiratoryNights] is its own count for the same reason: not every night has a
 * respiratory-rate reading at all (see [SleepEpisode.avgRespiratoryRate]).
 *
 * Every average is `null` when its own denominator is zero -- an honest "nothing to
 * average", never a fabricated zero.
 */
data class SleepAverages(
    val totalNights: Int,
    val consideredNights: Int,
    val avgDurationMinutes: Double?,
    val avgAwakenings: Double?,
    val avgAwakeningsDurationMinutes: Double?,
    val avgEfficiency: Double?,
    val respiratoryNights: Int,
    val avgRespiratoryRate: Double?,
    val stageNights: Int,
    val avgDeepMinutes: Double?,
    val avgRemMinutes: Double?,
    val avgLightMinutes: Double?,
)

/**
 * Computes [SleepAverages] over [window] from [nights] -- expected sorted ascending by
 * [SleepEpisode.wokeAt] (exactly what [SleepReader.loadNights] returns), since
 * [SleepAverageWindow.nightCount] is taken from the *end* of the list (the most recent
 * nights), not the start. [nights] need not already be filtered to
 * [SleepEpisodeKind.NIGHT] alone -- callers in practice always pass exactly that, since
 * that is what [SleepReader.loadNights] itself returns, but this stays defensive the same
 * way [sleepNightToNotify] does.
 */
fun computeSleepAverages(nights: List<SleepEpisode>, window: SleepAverageWindow): SleepAverages {
    val inWindow = nights.filter { it.kind == SleepEpisodeKind.NIGHT }
        .let { window.nightCount?.let(it::takeLast) ?: it }
    val considered = inWindow.filterNot { it.isInProgress || it.hasDataGap }

    fun avg(selector: (SleepEpisode) -> Double): Double? =
        considered.takeIf { it.isNotEmpty() }?.let { list -> list.sumOf(selector) / list.size }

    val withRespiratory = considered.mapNotNull { it.avgRespiratoryRate }

    val stageBreakdowns = considered.mapNotNull { episode ->
        when (val source = resolveSleepPhases(episode)) {
            is SleepPhaseSource.Measured -> sleepPhaseBreakdown(source.minutes)
            is SleepPhaseSource.Estimated -> sleepPhaseBreakdown(source.minutes)
            SleepPhaseSource.NotEstimable -> null
        }
    }
    fun avgPhase(phase: SleepPhase): Double? =
        stageBreakdowns.takeIf { it.isNotEmpty() }?.let { list -> list.sumOf { (it[phase] ?: 0).toDouble() } / list.size }

    return SleepAverages(
        totalNights = inWindow.size,
        consideredNights = considered.size,
        avgDurationMinutes = avg { it.durationAsleepMinutes.toDouble() },
        avgAwakenings = avg { it.awakenings.toDouble() },
        avgAwakeningsDurationMinutes = avg { it.awakeningsDurationMinutes.toDouble() },
        avgEfficiency = avg { it.sleepEfficiency },
        respiratoryNights = withRespiratory.size,
        avgRespiratoryRate = withRespiratory.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size },
        stageNights = stageBreakdowns.size,
        avgDeepMinutes = avgPhase(SleepPhase.DEEP),
        avgRemMinutes = avgPhase(SleepPhase.REM),
        avgLightMinutes = avgPhase(SleepPhase.LIGHT),
    )
}
