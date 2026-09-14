package ch.kevinjordil.helion.ui.sleep

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.ui.metric.Reading
import ch.kevinjordil.helion.ui.quality.Baseline
import ch.kevinjordil.helion.ui.quality.computeBaseline
import ch.kevinjordil.helion.ui.quality.personalBaselineMessage
import ch.kevinjordil.helion.ui.quality.placeAgainstBaseline
import ch.kevinjordil.helion.ui.quality.referenceForSleepDuration
import ch.kevinjordil.helion.ui.quality.referenceMessage
import ch.kevinjordil.helion.ui.theme.HelionCardSpacing
import ch.kevinjordil.helion.ui.theme.HelionColors
import ch.kevinjordil.helion.ui.theme.HelionScreenEdgeMargin
import ch.kevinjordil.helion.ui.theme.HelionStatItem
import ch.kevinjordil.helion.ui.theme.HelionSurface
import ch.kevinjordil.helion.ui.theme.HelionSurfacePadding
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")

/**
 * [date] prefixed with its French weekday abbreviation (see `R.array.weekday_short`,
 * Monday-first exactly like [java.time.DayOfWeek.getValue]) -- e.g. "Mer 24/08" -- so a
 * night's date is never shown without which day of the week it was. Abbreviated to three
 * letters to stay inside the same width budget as the bare date; see
 * SleepDateWidthTest. Internal rather than private: [ch.kevinjordil.helion.ui.sleep]'s
 * history-list row (see `SleepHistory.kt`) formats the same date the same way, and a
 * night's date must never read differently in the two places it appears.
 */
internal fun weekdayDateText(date: LocalDate, weekdayAbbreviations: List<String>): String =
    "${weekdayAbbreviations[date.dayOfWeek.value - 1]} ${DATE_FORMAT.format(date)}"

/**
 * The night's duration, sized down from [HelionType.hero]: a full 88sp hero numeral wraps
 * a ten-hour-plus night onto a second line on any narrow phone, which is exactly the bug
 * this style exists to fix. Verified against the widest value the format can realistically
 * show (a near-24h night, "23 h 59") at the app's narrowest supported width (320dp) by
 * DurationTextWidthTest -- see that test for the actual measurement.
 */
private val SLEEP_DURATION_STYLE = HelionType.hero.copy(fontSize = 40.sp, lineHeight = 44.sp)

/**
 * The root Column's own horizontal inset, now that the selected-night and averages cards sit
 * on raised [ch.kevinjordil.helion.ui.theme.HelionSurface]s rather than directly against it
 * -- see [HelionSurfacePadding] and this screen's own [SleepScreen] kdoc comment for why the two add
 * up to exactly the 20dp every Sommeil width test still measures against.
 */

/** A card's own internal padding: [HelionScreenEdgeMargin] plus this is the historical 20dp inset. */

/**
 * Sommeil: one selected night's full detail (the most recent by default), with
 * previous/next affordances to step through the roughly-last-month of recorded nights, and
 * the same nights again below as a tappable list -- a second way to jump straight to one.
 * Both routes land on exactly the same detail card, built entirely from [SleepReader] and
 * [segmentSleepEpisodes]; this composable only renders what it is handed.
 *
 * Night-by-night browsing was chosen over a calendar picker: every night this screen can
 * show already lives in one flat, chronologically-ordered list (see [SleepReader.loadNights]
 * -- roughly the last month), so stepping through it or tapping an entry directly are both
 * already free of a fresh query per date; a calendar's main advantage, jumping straight to
 * an arbitrary date, is not very different from tapping the entry for that date in the
 * history list this screen already shows.
 *
 * The two states [SleepEpisode.isInProgress] and [SleepEpisode.hasDataGap] are never
 * silently absorbed into a normal-looking number: both suppress the quality comparisons
 * (a provisional or untrustworthy duration has nothing honest to say "usual" or "in range"
 * about) and both surface their own explicit note instead. Since every entry in [loaded] is
 * an actual recorded episode, a selected night is never blank -- at worst it is one of
 * those two flagged states, said outright rather than shown as an empty card.
 */
@Composable
fun SleepScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    val reader = remember(container) { SleepReader(container.database) }

    var nights by remember { mutableStateOf<List<SleepEpisode>?>(null) }
    var baseline by remember { mutableStateOf<Baseline?>(null) }
    // Every night the archive has ever recorded, loaded once and independently of [nights]
    // (which stays capped at SleepReader's own ~30-day lookback): the averages panel's own
    // SleepAverageWindow.ALL option must be able to answer "everything", not just the last
    // month the history list and browsing already cover.
    var allNights by remember { mutableStateOf<List<SleepEpisode>?>(null) }
    var averageWindow by rememberSaveable { mutableStateOf(SleepAverageWindow.LAST_7) }
    // Index into `nights`, ascending by [SleepEpisode.wokeAt] (oldest first, exactly as
    // [SleepReader.loadNights] returns it) -- so index 0 is the oldest night on screen and
    // the last index is the most recent, which is also this state's initial value.
    var selectedIndex by remember { mutableStateOf(0) }

    /**
     * Naps, listed below the nights and never mixed into them: they are deliberately absent
     * from the averages and from the morning notification, because a forty-minute doze
     * averaged in with nights would drag the figure down as though it were one.
     */
    var naps by remember { mutableStateOf<List<SleepEpisode>>(emptyList()) }

    /**
     * Which nap is open in the detail card, or null while a night is. Held separately from
     * [selectedIndex] rather than merged into one list so the previous/next controls always
     * step through episodes of the same kind -- stepping from a night into an afternoon
     * doze and back out again is not browsing, it is losing your place.
     */
    var selectedNapIndex by remember { mutableStateOf<Int?>(null) }

    // Overlay toggles for the night chart, hoisted here rather than remembered inside
    // SelectedNightCard so they survive stepping to a different night -- exactly what
    // "persist while he browses between nights" requires. Off by default; heart rate
    // itself is not a toggle, it is always shown.
    var showRespiratoryOverlay by rememberSaveable { mutableStateOf(false) }
    var showMovementOverlay by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis() / 1000
        val loaded = reader.loadNights(now)
        nights = loaded
        selectedIndex = loaded.lastIndex
        // The baseline is computed only from completed, trustworthy nights: an
        // in-progress or gappy duration is not a reading Helion can vouch for, and must
        // not quietly pull the owner's own history off centre.
        val history = loaded
            .filterNot { it.isInProgress || it.hasDataGap }
            .map { Reading(it.wokeAt, it.durationAsleepMinutes / 60.0) }
        baseline = computeBaseline(history)
        allNights = reader.loadNights(now, lookbackDays = null)
        naps = reader.loadNaps(now)
    }

    val loaded = nights ?: return

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = HelionScreenEdgeMargin, vertical = HelionCardSpacing),
        verticalArrangement = Arrangement.spacedBy(HelionCardSpacing),
    ) {
        Text(
            stringResource(R.string.tab_sleep), style = HelionType.headline, color = colors.textPrimary,
            modifier = Modifier.padding(horizontal = HelionSurfacePadding),
        )
        val selected = loaded.getOrNull(selectedIndex)
        if (selected == null) {
            Text(stringResource(R.string.sleep_no_nights), style = HelionType.body, color = colors.textSecondary)
            return
        }

        // Every other recorded night, most recent first, indices preserved so tapping one
        // can select it directly -- the second of the two ways to land on the same detail
        // card as stepping with the previous/next controls.
        val history = loaded.mapIndexed { index, episode -> index to episode }
            .filter { (index, _) -> index != selectedIndex }
            .reversed()

        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(HelionCardSpacing)) {
            item {
                // A nap, once opened, takes over the same card: same chart, same figures.
                // Its own previous/next steps through naps, so the two kinds never
                // interleave under the arrows.
                val openNap = selectedNapIndex?.let { naps.getOrNull(it) }
                if (openNap != null) {
                    val napIndex = selectedNapIndex!!
                    SelectedNightCard(
                        episode = openNap,
                        baseline = baseline,
                        hasPrevious = napIndex > 0,
                        hasNext = napIndex < naps.lastIndex,
                        onPrevious = { selectedNapIndex = napIndex - 1 },
                        onNext = { selectedNapIndex = napIndex + 1 },
                        showRespiratoryOverlay = showRespiratoryOverlay,
                        onShowRespiratoryOverlayChange = { showRespiratoryOverlay = it },
                        showMovementOverlay = showMovementOverlay,
                        onShowMovementOverlayChange = { showMovementOverlay = it },
                    )
                } else {
                    SelectedNightCard(
                        episode = selected,
                        baseline = baseline,
                        hasPrevious = selectedIndex > 0,
                        hasNext = selectedIndex < loaded.lastIndex,
                        onPrevious = { selectedIndex -= 1 },
                        onNext = { selectedIndex += 1 },
                        showRespiratoryOverlay = showRespiratoryOverlay,
                        onShowRespiratoryOverlayChange = { showRespiratoryOverlay = it },
                        showMovementOverlay = showMovementOverlay,
                        onShowMovementOverlayChange = { showMovementOverlay = it },
                    )
                }
            }
            allNights?.let { all ->
                item {
                    SleepAveragesSection(
                        nights = all,
                        window = averageWindow,
                        onWindowChange = { averageWindow = it },
                    )
                }
            }
            if (history.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.sleep_history_title),
                        style = HelionType.title,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(start = HelionSurfacePadding, end = HelionSurfacePadding, top = 8.dp),
                    )
                }
                items(history, key = { (index, _) -> index }) { (index, episode) ->
                    HistoryRow(
                        episode,
                        onClick = {
                            selectedIndex = index
                            selectedNapIndex = null
                        },
                        modifier = Modifier.padding(horizontal = HelionSurfacePadding),
                    )
                }
            }

            // Naps, under the nights and clearly apart from them. They were classified from
            // the start so they could be kept out of the night averages, but until now
            // nothing ever showed them -- so a sleep the app filed as a nap was a sleep the
            // owner could not reach at all.
            if (naps.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.sleep_naps_title),
                        style = HelionType.title,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(start = HelionSurfacePadding, end = HelionSurfacePadding, top = 8.dp),
                    )
                }
                itemsIndexed(naps.reversed(), key = { _, episode -> "nap:${episode.wokeAt}" }) { reversedIndex, episode ->
                    HistoryRow(
                        episode,
                        onClick = { selectedNapIndex = naps.lastIndex - reversedIndex },
                        modifier = Modifier.padding(horizontal = HelionSurfacePadding),
                        // A nap is identified by when in the day it happened: two on the
                        // same date are otherwise two identical rows.
                        startTime = CLOCK_FORMAT.format(Instant.ofEpochSecond(episode.fellAsleepAt)),
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedNightCard(
    episode: SleepEpisode,
    baseline: Baseline?,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    showRespiratoryOverlay: Boolean,
    onShowRespiratoryOverlayChange: (Boolean) -> Unit,
    showMovementOverlay: Boolean,
    onShowMovementOverlayChange: (Boolean) -> Unit,
) {
    val colors = HelionThemeTokens.colors
    val hours = episode.durationAsleepMinutes / 60
    val minutes = episode.durationAsleepMinutes % 60
    val weekdays = stringArrayResource(R.array.weekday_short).toList()

    // One rectangle, one idea: the night used to be a single card carrying the duration
    // hero, the bedtime/wake pair, the phase breakdown, the chart and both the awakening
    // count and the efficiency percentage -- none of which form one whole with each other.
    // This is now a stack of surfaces, each holding either one measure or a genuine whole
    // (bedtime+wake are the two ends of the same night; the three stages sum to the night's
    // duration), sized so the duration -- the answer to "how did he sleep" -- reads as the
    // most important figure on the screen.
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HelionCardSpacing)) {
        HelionSurface(
            modifier = Modifier.fillMaxWidth(),
            padding = androidx.compose.foundation.layout.PaddingValues(HelionSurfacePadding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            // Now that this card holds only the headline and not the chart too, it can
            // carry the screen's colour without becoming a slab -- see HelionSurface on why
            // the tint is a gradient rather than a flat wash.
            tint = colors.accentViolet,
        ) {
            // The date is centred by giving it the row's spare width and centring inside
            // it, not by relying on the two icon buttons happening to be equally wide --
            // the day timeline's copy of this control relied on that and drifted left.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrevious, enabled = hasPrevious) {
                    Icon(
                        Icons.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.sleep_previous_night),
                        tint = if (hasPrevious) colors.textSecondary else colors.textTertiary,
                    )
                }
                Text(
                    weekdayDateText(episode.date, weekdays),
                    style = HelionType.label,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onNext, enabled = hasNext) {
                    Icon(
                        Icons.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.sleep_next_night),
                        tint = if (hasNext) colors.textSecondary else colors.textTertiary,
                    )
                }
            }

            Text(
                stringResource(R.string.sleep_duration_format, hours.toInt(), minutes.toInt()),
                style = SLEEP_DURATION_STYLE,
                color = colors.accentViolet,
                softWrap = false,
            )

            if (episode.isInProgress) {
                Text(
                    stringResource(R.string.sleep_in_progress_note),
                    style = HelionType.bodySmall,
                    color = colors.accentAmber,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (episode.hasDataGap) {
                Text(
                    stringResource(R.string.sleep_data_gap_note),
                    style = HelionType.bodySmall,
                    color = colors.accentAmber,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // Nights only. Both comparisons are defined over a night's duration -- the
            // owner's own 30-night baseline, and the 7-9h reference range -- so applying
            // them to a nap would report a forty-minute doze as "below the recommended
            // range" and count it against his usual, which is nonsense in both directions.
            if (episode.kind == SleepEpisodeKind.NIGHT && !episode.isInProgress && !episode.hasDataGap) {
                val (personalRes, personalAmber) = personalBaselineMessage(
                    placeAgainstBaseline(episode.durationAsleepMinutes / 60.0, baseline),
                )
                val (referenceRes, referenceAmber) = referenceMessage(
                    "sleep_duration",
                    referenceForSleepDuration(episode.durationAsleepMinutes / 60.0),
                )
                Text(
                    stringResource(personalRes),
                    style = HelionType.bodySmall,
                    color = if (personalAmber) colors.accentAmber else colors.textSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(stringResource(referenceRes), style = HelionType.bodySmall, color = if (referenceAmber) colors.accentAmber else colors.textTertiary)
            }

        }

        // The chart has its own card rather than riding along under the duration. Together
        // they were a single surface running almost the whole screen, which read as one
        // slab of background regardless of what colour it was: they are two different
        // questions -- how long, and what the night looked like -- so they are two cards.
        HelionSurface(
            modifier = Modifier.fillMaxWidth(),
            padding = androidx.compose.foundation.layout.PaddingValues(HelionSurfacePadding),
        ) {
            NightChartSection(
                episode = episode,
                showRespiratory = showRespiratoryOverlay,
                onShowRespiratoryChange = onShowRespiratoryOverlayChange,
                showMovement = showMovementOverlay,
                onShowMovementChange = onShowMovementOverlayChange,
            )
        }

        // Bedtime and wake time: the two ends of the same night, so they keep sharing one
        // surface rather than each getting its own.
        HelionSurface(
            modifier = Modifier.fillMaxWidth(),
            padding = androidx.compose.foundation.layout.PaddingValues(HelionSurfacePadding),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HelionStatItem(stringResource(R.string.sleep_fell_asleep), CLOCK_FORMAT.format(Instant.ofEpochSecond(episode.fellAsleepAt)), Modifier.weight(1f), centred = true)
                HelionStatItem(stringResource(R.string.sleep_woke_at), CLOCK_FORMAT.format(Instant.ofEpochSecond(episode.wokeAt)), Modifier.weight(1f), centred = true)
            }
        }

        // Computed once here (rather than inside NightChartSection, which resolves its own
        // copy for the chart) so the per-stage breakdown below uses the same source.
        val phaseSource = remember(episode) { resolveSleepPhases(episode) }
        // Deep/REM/light: three stages that together make up the night's duration, so they
        // stay on one surface -- the same rule that splits awakenings and efficiency apart.
        when (phaseSource) {
            is SleepPhaseSource.Measured -> HelionSurface(
                modifier = Modifier.fillMaxWidth(),
                padding = androidx.compose.foundation.layout.PaddingValues(HelionSurfacePadding),
            ) { SleepPhaseBreakdown(phaseSource.minutes) }
            is SleepPhaseSource.Estimated -> HelionSurface(
                modifier = Modifier.fillMaxWidth(),
                padding = androidx.compose.foundation.layout.PaddingValues(HelionSurfacePadding),
            ) { SleepPhaseBreakdown(phaseSource.minutes) }
            SleepPhaseSource.NotEstimable -> Unit
        }

        // The awakening count and the sleep efficiency are different kinds of measure -- an
        // event count and a ratio -- but each is a single figure, and a whole card apiece
        // for one figure was mostly empty space. They share one card as two columns of the
        // same row: the row is what makes a set of them, the way the phase trio above
        // already works. "12 · 24 min" is the widest thing that has to fit a half-width
        // column here; SleepScreenWidthTest holds that bound.
        HelionSurface(
            modifier = Modifier.fillMaxWidth(),
            padding = androidx.compose.foundation.layout.PaddingValues(HelionSurfacePadding),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HelionStatItem(
                    stringResource(R.string.sleep_awakenings),
                    stringResource(R.string.sleep_awakenings_value, episode.awakenings, episode.awakeningsDurationMinutes),
                    Modifier.weight(1f),
                    centred = true,
                )
                HelionStatItem(
                    stringResource(R.string.sleep_efficiency),
                    "${(episode.sleepEfficiency * 100).toInt()} %",
                    Modifier.weight(1f),
                    centred = true,
                )
            }
        }
    }
}

/**
 * The per-stage breakdown (profond, paradoxal, léger) for either [SleepPhaseSource.Measured]
 * or [SleepPhaseSource.Estimated] -- the hypnogram itself now lives inside
 * [NightChartSection], stacked directly under the heart-rate line on the same time axis, so
 * this breakdown is the only phase content left in [SelectedNightCard]'s upper section.
 *
 * Two columns, not three: at this value size, a single phase's widest
 * plausible duration ("23 h 59", the same bound DurationTextWidthTest uses
 * for the whole night) does not fit a third-width column -- see
 * SleepScreenWidthTest. Two rows of two, the same fix already used above,
 * one column short: three items into two columns leaves the third
 * ([SleepPhase.LIGHT]) alone on its own full-width row.
 */
@Composable
private fun SleepPhaseBreakdown(minutes: List<PhaseMinute>) {
    val breakdown = sleepPhaseBreakdown(minutes)
    // One row of three, full width: the three phases are one quantity split three ways and
    // read as a comparison, which a two-plus-one layout breaks. Fits at a third width only
    // because phaseDurationText is compact ("1h36", not "1 h 36") -- see SleepScreenWidthTest.
    Row(
        // 6dp, not the 8dp used elsewhere: "PARADOXAL" is the widest label in the app and
        // overflows a third-width column by under a dp at an enlarged font scale with 8dp
        // gaps. Tightening the gap keeps the word whole -- see DurationTextWidthTest.
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HelionStatItem(stringResource(R.string.sleep_phase_deep), phaseDurationText(breakdown[SleepPhase.DEEP] ?: 0), Modifier.weight(1f), centred = true)
        HelionStatItem(stringResource(R.string.sleep_phase_rem), phaseDurationText(breakdown[SleepPhase.REM] ?: 0), Modifier.weight(1f), centred = true)
        HelionStatItem(stringResource(R.string.sleep_phase_light), phaseDurationText(breakdown[SleepPhase.LIGHT] ?: 0), Modifier.weight(1f), centred = true)
    }
}

/**
 * What each phase colour on the hypnogram means -- see [HelionColors]'s kdoc on
 * [HelionColors.phaseAwake]/[HelionColors.phaseLight]/[HelionColors.phaseRem]/
 * [HelionColors.phaseDeep] for why these four exist and are not [HelionColors.accentViolet]
 * or [HelionColors.accentAmber].
 */
internal fun phaseColors(colors: HelionColors) = mapOf(
    SleepPhase.AWAKE to colors.phaseAwake,
    SleepPhase.LIGHT to colors.phaseLight,
    SleepPhase.REM to colors.phaseRem,
    SleepPhase.DEEP to colors.phaseDeep,
)

internal fun phaseLabelRes(phase: SleepPhase): Int = when (phase) {
    SleepPhase.AWAKE -> R.string.sleep_phase_awake
    SleepPhase.REM -> R.string.sleep_phase_rem
    SleepPhase.LIGHT -> R.string.sleep_phase_light
    SleepPhase.DEEP -> R.string.sleep_phase_deep
}

private fun phaseDurationText(minutesInPhase: Int): String {
    val hours = minutesInPhase / 60
    val minutes = minutesInPhase % 60
    // Always the hours form, even under an hour ("0h47"): the three phases sit side by side
    // as a comparison, so their digits should align column to column, and "59 min" does not
    // fit a third-width column at this value size anyway -- see DurationTextWidthTest.
    return "%dh%02d".format(hours, minutes)
}


/**
 * Sommeil's averages panel: a window selector -- the same visual pattern
 * [ch.kevinjordil.helion.ui.metric.MetricScreen]'s own `RangeSelector` uses for Jour/Semaine/Mois,
 * reused here rather than inventing a second control -- and the figures [computeSleepAverages]
 * hands back for that window.
 *
 * Every "how many nights" figure [SleepAverages] carries is shown, never only the number
 * itself: [SleepAverages.consideredNights] against [SleepAverages.totalNights] right under
 * the header (so "30 dernières nuits" against an archive with only four recorded nights
 * reads honestly), and [SleepAverages.stageNights] again next to the phase breakdown, since
 * that count can be smaller still. A `null` average (its own denominator empty) renders as
 * [R.string.sleep_average_value_missing], never a fabricated zero.
 */
@Composable
private fun SleepAveragesSection(nights: List<SleepEpisode>, window: SleepAverageWindow, onWindowChange: (SleepAverageWindow) -> Unit) {
    val colors = HelionThemeTokens.colors
    val averages = remember(nights, window) { computeSleepAverages(nights, window) }

    // The section title, window selector and "N nuits sur M" line are the section's own
    // header, not a measure, so they sit above the cards the same way Sommeil's own history
    // title does -- see HistoryRow's Text just below in this file. Each average below then
    // gets its own surface (or, for the three stages, one shared surface for the whole they
    // form together) instead of three unrelated averages sharing one row on one card.
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HelionCardSpacing)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = HelionSurfacePadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.sleep_average_section_title),
                style = HelionType.title,
                color = colors.textPrimary,
            )
            AverageWindowSelector(selected = window, onSelect = onWindowChange)
            if (averages.consideredNights == 0) {
                Text(stringResource(R.string.sleep_average_no_nights), style = HelionType.bodySmall, color = colors.textTertiary)
                return@Column
            }
            Text(
                // A plural, not a format string: a single night read as "1 nuits sur 1".
                pluralStringResource(
                    R.plurals.sleep_average_nights_basis,
                    averages.consideredNights,
                    averages.consideredNights,
                    averages.totalNights,
                ),
                style = HelionType.bodySmall,
                color = colors.textTertiary,
            )
        }

        if (averages.consideredNights == 0) return@Column

        // Four single figures had a card each, and four cards holding one figure apiece is
        // mostly padding. They share one card as two rows of columns -- the row is what
        // makes a set of them, exactly as it does for the phase trio below.
        HelionSurface(
            modifier = Modifier.fillMaxWidth(),
            padding = androidx.compose.foundation.layout.PaddingValues(HelionSurfacePadding),
            verticalArrangement = Arrangement.spacedBy(HelionCardSpacing),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HelionStatItem(
                    stringResource(R.string.sleep_average_duration_label),
                    averageDurationText(averages.avgDurationMinutes),
                    Modifier.weight(1f),
                    centred = true,
                )
                HelionStatItem(
                    stringResource(R.string.sleep_awakenings),
                    averages.avgAwakenings?.let {
                        stringResource(
                            R.string.sleep_awakenings_value,
                            it.roundToInt(),
                            (averages.avgAwakeningsDurationMinutes ?: 0.0).roundToInt(),
                        )
                    } ?: stringResource(R.string.sleep_average_value_missing),
                    Modifier.weight(1f),
                    centred = true,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HelionStatItem(
                    stringResource(R.string.sleep_efficiency),
                    averages.avgEfficiency?.let { "${(it * 100).toInt()} %" } ?: stringResource(R.string.sleep_average_value_missing),
                    Modifier.weight(1f),
                    centred = true,
                )
                HelionStatItem(
                    stringResource(R.string.metric_respiratory_rate),
                    averages.avgRespiratoryRate?.let { "${it.roundToInt()}" } ?: stringResource(R.string.sleep_average_value_missing),
                    Modifier.weight(1f),
                    // A bare "16" says nothing on its own -- this is the only figure in the
                    // panel whose unit is not implied by its label. Dropped when there is no
                    // value: "resp/min" under a dash is a unit for nothing.
                    unit = averages.avgRespiratoryRate?.let { stringResource(R.string.unit_breaths_per_minute) },
                    centred = true,
                )
            }
        }

        // The three stages together make up the night's duration, so they keep sharing one
        // surface -- the same rule SelectedNightCard's own phase breakdown follows.
        HelionSurface(
            modifier = Modifier.fillMaxWidth(),
            padding = androidx.compose.foundation.layout.PaddingValues(HelionSurfacePadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HelionStatItem(stringResource(R.string.sleep_phase_deep), averagePhaseDurationText(averages.avgDeepMinutes), Modifier.weight(1f), centred = true)
                HelionStatItem(stringResource(R.string.sleep_phase_rem), averagePhaseDurationText(averages.avgRemMinutes), Modifier.weight(1f), centred = true)
                HelionStatItem(stringResource(R.string.sleep_phase_light), averagePhaseDurationText(averages.avgLightMinutes), Modifier.weight(1f), centred = true)
            }
            Text(
                stringResource(R.string.sleep_average_stage_basis, averages.stageNights, averages.consideredNights),
                style = HelionType.bodySmall,
                color = colors.textTertiary,
            )
        }

    }
}

@Composable
private fun AverageWindowSelector(selected: SleepAverageWindow, onSelect: (SleepAverageWindow) -> Unit) {
    val colors = HelionThemeTokens.colors
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        SleepAverageWindow.entries.forEach { option ->
            Text(
                stringResource(option.labelRes),
                style = HelionType.label,
                color = if (option == selected) colors.accentViolet else colors.textTertiary,
                modifier = Modifier
                    .clickable { onSelect(option) }
                    .padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun averageDurationText(minutes: Double?): String {
    if (minutes == null) return stringResource(R.string.sleep_average_value_missing)
    val total = minutes.toLong()
    return stringResource(R.string.sleep_duration_format, (total / 60).toInt(), (total % 60).toInt())
}

@Composable
private fun averagePhaseDurationText(minutes: Double?): String {
    if (minutes == null) return stringResource(R.string.sleep_average_value_missing)
    val total = minutes.toLong()
    return "%dh%02d".format(total / 60, total % 60)
}

// HistoryRow now lives in SleepHistory.kt, alongside the per-row stage composition bar and
// the pure geometry it draws from -- see that file's own kdoc for why it was split out.
