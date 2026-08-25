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
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import ch.kevinjordil.helion.ui.ribbon.HypnogramRibbon
import ch.kevinjordil.helion.ui.ribbon.buildCategoryRibbon
import ch.kevinjordil.helion.ui.theme.HelionColors
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")

/**
 * [date] prefixed with its French weekday abbreviation (see `R.array.weekday_short`,
 * Monday-first exactly like [java.time.DayOfWeek.getValue]) -- e.g. "Mer 24/08" -- so a
 * night's date is never shown without which day of the week it was. Abbreviated to three
 * letters to stay inside the same width budget as the bare date; see
 * SleepDateWidthTest.
 */
private fun weekdayDateText(date: LocalDate, weekdayAbbreviations: List<String>): String =
    "${weekdayAbbreviations[date.dayOfWeek.value - 1]} ${DATE_FORMAT.format(date)}"

/** Buckets an episode's own span into roughly ten-minute slices, clamped to a sane range for very short or very long episodes. */
private const val EPISODE_BUCKET_MINUTES = 10
private const val MIN_EPISODE_BUCKETS = 24
private const val MAX_EPISODE_BUCKETS = 96

/**
 * The night's duration, sized down from [HelionType.hero]: a full 88sp hero numeral wraps
 * a ten-hour-plus night onto a second line on any narrow phone, which is exactly the bug
 * this style exists to fix. Verified against the widest value the format can realistically
 * show (a near-24h night, "23 h 59") at the app's narrowest supported width (320dp) by
 * DurationTextWidthTest -- see that test for the actual measurement.
 */
private val SLEEP_DURATION_STYLE = HelionType.hero.copy(fontSize = 40.sp, lineHeight = 44.sp)

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
    // Index into `nights`, ascending by [SleepEpisode.wokeAt] (oldest first, exactly as
    // [SleepReader.loadNights] returns it) -- so index 0 is the oldest night on screen and
    // the last index is the most recent, which is also this state's initial value.
    var selectedIndex by remember { mutableStateOf(0) }

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
    }

    val loaded = nights ?: return

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // No page header here: the bottom navigation bar already names this destination
        // (icon + "Sommeil" label), so a second, purely decorative "SOMMEIL" line at the
        // top only pushed real content down for nothing.
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

        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
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
            if (history.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.sleep_history_title).uppercase(),
                        style = HelionType.label,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(history, key = { (index, _) -> index }) { (index, episode) ->
                    HistoryRow(episode, onClick = { selectedIndex = index })
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

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onPrevious, enabled = hasPrevious) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.sleep_previous_night),
                    tint = if (hasPrevious) colors.textSecondary else colors.textTertiary,
                )
            }
            Text(weekdayDateText(episode.date, weekdays), style = HelionType.label, color = colors.textSecondary)
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

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatItem(stringResource(R.string.sleep_fell_asleep), CLOCK_FORMAT.format(Instant.ofEpochSecond(episode.fellAsleepAt)), Modifier.weight(1f))
            StatItem(stringResource(R.string.sleep_woke_at), CLOCK_FORMAT.format(Instant.ofEpochSecond(episode.wokeAt)), Modifier.weight(1f))
        }

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

        // Computed once here (rather than inside SleepPhaseSection) so the same
        // measured-vs-estimated source drives both the breakdown placed right below the
        // duration reference line and the title/hypnogram further down.
        val phaseSource = remember(episode) { resolveSleepPhases(episode) }

        if (!episode.isInProgress && !episode.hasDataGap) {
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

        when (phaseSource) {
            is SleepPhaseSource.Measured -> SleepPhaseBreakdown(phaseSource.minutes)
            is SleepPhaseSource.Estimated -> SleepPhaseBreakdown(phaseSource.minutes)
            SleepPhaseSource.NotEstimable -> Unit
        }

        // Each on its own full-width line, not sharing a row: "12 · 24 min" is already a
        // composed count-plus-duration phrase, and a disturbed night's widest plausible
        // form of it does not fit a half-width column at this value size (see
        // SleepScreenWidthTest) -- the same composed-string wrapping this screen's other
        // fixes are for. The full row width is what actually gives it room.
        Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatItem(
                stringResource(R.string.sleep_awakenings),
                stringResource(R.string.sleep_awakenings_value, episode.awakenings, episode.awakeningsDurationMinutes),
                Modifier.fillMaxWidth(),
            )
            StatItem(stringResource(R.string.sleep_efficiency), "${(episode.sleepEfficiency * 100).toInt()} %", Modifier.fillMaxWidth())
        }

        NightChartSection(
            episode = episode,
            showRespiratory = showRespiratoryOverlay,
            onShowRespiratoryChange = onShowRespiratoryOverlayChange,
            showMovement = showMovementOverlay,
            onShowMovementChange = onShowMovementOverlayChange,
            modifier = Modifier.padding(top = 16.dp),
        )

        SleepPhaseSection(episode, phaseSource)
    }
}

/**
 * Phase title and hypnogram for [episode] -- see [resolveSleepPhases] for which source
 * (the device's own measured segments, or the heuristic estimator) is actually in use,
 * and why an episode can only ever be in one of those two states plus
 * [SleepPhaseSource.NotEstimable]. [source] is resolved once by the caller so it stays in
 * sync with the per-stage breakdown rendered separately, higher up the screen (see
 * [SleepPhaseBreakdown]). Only the estimated path spells out "estimé", both in its own
 * section title and its own not-estimable fallback: that is the one place Helion shows
 * something it did not measure, and it must never read as a plain fact next to the
 * numbers it did measure. The measured path uses a plain title and never mentions
 * estimation at all.
 */
@Composable
private fun SleepPhaseSection(episode: SleepEpisode, source: SleepPhaseSource) {
    val colors = HelionThemeTokens.colors

    val titleRes = if (source is SleepPhaseSource.Estimated) {
        R.string.sleep_phase_title_estimated
    } else {
        R.string.sleep_phase_title
    }

    Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(titleRes).uppercase(), style = HelionType.label, color = colors.textSecondary)

        when (source) {
            is SleepPhaseSource.NotEstimable ->
                Text(stringResource(R.string.sleep_phase_not_estimable), style = HelionType.bodySmall, color = colors.textSecondary)

            is SleepPhaseSource.Measured -> SleepPhaseHypnogram(episode, source.minutes)
            is SleepPhaseSource.Estimated -> SleepPhaseHypnogram(episode, source.minutes)
        }
    }
}

/** The hypnogram shared by [SleepPhaseSource.Measured] and [SleepPhaseSource.Estimated] -- only the section title above differs between the two. */
@Composable
private fun SleepPhaseHypnogram(episode: SleepEpisode, minutes: List<PhaseMinute>) {
    val colors = HelionThemeTokens.colors
    val phaseColor = phaseColors(colors)
    val lanes = listOf(SleepPhase.AWAKE, SleepPhase.REM, SleepPhase.LIGHT, SleepPhase.DEEP)
    val phaseLabel = lanes.associateWith { phase -> stringResource(phaseLabelRes(phase)) }
    // One lane per stage, éveil to profond, so a transition between stages is
    // a visible jump between lanes -- a single blended track (or a stacked
    // share breakdown) never shows *when* the night switched stages, only how
    // much of each it had. See HypnogramRibbon's kdoc.
    HypnogramRibbon(
        bars = buildCategoryRibbon(
            items = minutes.map { it.timestamp to it.phase },
            windowStart = episode.fellAsleepAt,
            windowEnd = episode.wokeAt + 60,
            bucketCount = episodeBucketCount(episode),
        ),
        lanes = lanes,
        laneColor = { phase -> phaseColor.getValue(phase) },
        laneLabel = { phase -> phaseLabel.getValue(phase) },
        labelColor = colors.textSecondary,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The per-stage breakdown (profond, paradoxal, léger) for either [SleepPhaseSource.Measured]
 * or [SleepPhaseSource.Estimated] -- placed directly below the sleep-duration reference
 * line in [SelectedNightCard], ahead of the title and hypnogram it used to sit under.
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
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatItem(stringResource(R.string.sleep_phase_deep), phaseDurationText(breakdown[SleepPhase.DEEP] ?: 0), Modifier.weight(1f))
            StatItem(stringResource(R.string.sleep_phase_rem), phaseDurationText(breakdown[SleepPhase.REM] ?: 0), Modifier.weight(1f))
        }
        StatItem(stringResource(R.string.sleep_phase_light), phaseDurationText(breakdown[SleepPhase.LIGHT] ?: 0), Modifier.fillMaxWidth())
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
    return if (hours > 0) "%d h %02d".format(hours, minutes) else "%d min".format(minutes)
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    Column(modifier = modifier) {
        Text(label.uppercase(), style = HelionType.labelSmall, color = colors.textTertiary)
        Text(value, style = HelionType.valueMedium, color = colors.textPrimary)
    }
}

@Composable
private fun HistoryRow(episode: SleepEpisode, onClick: () -> Unit) {
    val colors = HelionThemeTokens.colors
    val hours = episode.durationAsleepMinutes / 60
    val minutes = episode.durationAsleepMinutes % 60
    val weekdays = stringArrayResource(R.array.weekday_short).toList()
    val tag = when {
        episode.isInProgress -> stringResource(R.string.sleep_history_in_progress_tag)
        episode.hasDataGap -> stringResource(R.string.sleep_history_incomplete_tag)
        else -> null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Button)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(weekdayDateText(episode.date, weekdays), style = HelionType.body, color = colors.textSecondary)
        Column(horizontalAlignment = Alignment.End) {
            Text(
                stringResource(R.string.sleep_duration_format, hours.toInt(), minutes.toInt()),
                style = HelionType.body,
                color = colors.textPrimary,
            )
            tag?.let { Text(it, style = HelionType.labelSmall, color = colors.accentAmber) }
        }
    }
}

private fun episodeBucketCount(episode: SleepEpisode): Int {
    val spanMinutes = (episode.wokeAt - episode.fellAsleepAt) / 60 + 1
    return (spanMinutes / EPISODE_BUCKET_MINUTES).toInt().coerceIn(MIN_EPISODE_BUCKETS, MAX_EPISODE_BUCKETS)
}
