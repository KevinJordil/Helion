package ch.kevinjordil.helion.ui.sleep

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
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
import ch.kevinjordil.helion.ui.theme.HelionIcons
import ch.kevinjordil.helion.ui.theme.HelionMotion
import ch.kevinjordil.helion.ui.theme.HelionScreenEdgeMargin
import ch.kevinjordil.helion.ui.theme.HelionScreenHeader
import ch.kevinjordil.helion.ui.theme.HelionSectionLabel
import ch.kevinjordil.helion.ui.theme.HelionSegmentedControl
import ch.kevinjordil.helion.ui.theme.HelionStatRow
import ch.kevinjordil.helion.ui.theme.HelionSurface
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import ch.kevinjordil.helion.ui.theme.HorizontalRule
import ch.kevinjordil.helion.ui.theme.StatCell
import ch.kevinjordil.helion.ui.theme.StatusDot
import ch.kevinjordil.helion.ui.theme.helionWell
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")

/** "Lun 16/11": the short weekday from [weekdayAbbreviations] and the day/month. */
internal fun weekdayDateText(date: LocalDate, weekdayAbbreviations: List<String>): String =
    "${weekdayAbbreviations[date.dayOfWeek.value - 1]} ${DATE_FORMAT.format(date)}"

/** The night's duration: the screen's one dominant figure (40sp keeps "10 h 59" on one line at 320dp). */
private val SLEEP_DURATION_STYLE = HelionType.hero.copy(fontSize = 40.sp, lineHeight = 44.sp)

/**
 * Sommeil: the selected night first -- its duration, then how it divided into phases, then
 * the hypnogram and the heart rate through it -- then averages over recent nights and the
 * history to step back through. Naps sit in their own section and open in the same detail.
 */
@Composable
fun SleepScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    val reader = remember(container) { SleepReader(container.database) }

    var nights by remember { mutableStateOf<List<SleepEpisode>?>(null) }
    var baseline by remember { mutableStateOf<Baseline?>(null) }
    var allNights by remember { mutableStateOf<List<SleepEpisode>?>(null) }
    var averageWindow by rememberSaveable { mutableStateOf(SleepAverageWindow.LAST_7) }
    var selectedIndex by remember { mutableStateOf(0) }
    var naps by remember { mutableStateOf<List<SleepEpisode>>(emptyList()) }
    // A nap opened from the Siestes section replaces the night in the detail until another
    // night is picked; null means the detail shows the selected night.
    var selectedNapIndex by remember { mutableStateOf<Int?>(null) }
    var showRespiratoryOverlay by rememberSaveable { mutableStateOf(false) }
    var showMovementOverlay by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis() / 1000
        val loaded = reader.loadNights(now)
        nights = loaded
        selectedIndex = loaded.lastIndex
        // In-progress and gapped nights would drag the baseline: their durations are not final.
        val history = loaded
            .filterNot { it.isInProgress || it.hasDataGap }
            .map { Reading(it.wokeAt, it.durationAsleepMinutes / 60.0) }
        baseline = computeBaseline(history)
        allNights = reader.loadNights(now, lookbackDays = null)
        naps = reader.loadNaps(now)
    }

    val loaded = nights ?: return
    val weekdays = stringArrayResource(R.array.weekday_short).toList()
    val openNap = selectedNapIndex?.let { naps.getOrNull(it) }
    val selected = openNap ?: loaded.getOrNull(selectedIndex)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(HelionCardSpacing),
    ) {
        item {
            HelionScreenHeader(
                title = stringResource(R.string.tab_sleep),
                subtitle = selected?.let { episode ->
                    {
                        Text(
                            longDate(episode, openNap != null),
                            style = HelionType.bodySmall,
                            color = colors.textSecondary,
                        )
                    }
                },
                trailing = if (selected == null) null else {
                    {
                        val napIndex = selectedNapIndex
                        val hasPrevious = if (napIndex != null) napIndex > 0 else selectedIndex > 0
                        val hasNext = if (napIndex != null) napIndex < naps.lastIndex else selectedIndex < loaded.lastIndex
                        NightStepper(
                            hasPrevious = hasPrevious,
                            hasNext = hasNext,
                            onPrevious = { if (napIndex != null) selectedNapIndex = napIndex - 1 else selectedIndex -= 1 },
                            onNext = { if (napIndex != null) selectedNapIndex = napIndex + 1 else selectedIndex += 1 },
                        )
                    }
                },
            )
        }

        if (selected == null) {
            item {
                Text(
                    stringResource(R.string.sleep_no_nights),
                    style = HelionType.body,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = HelionScreenEdgeMargin + 14.dp),
                )
            }
            return@LazyColumn
        }

        item(key = "night") {
            AnimatedContent(
                targetState = selected,
                transitionSpec = { fadeIn(tween(HelionMotion.MEDIUM)) togetherWith fadeOut(tween(HelionMotion.SHORT)) },
                label = "night",
            ) { episode ->
                SelectedNight(
                    episode = episode,
                    baseline = baseline,
                    showRespiratoryOverlay = showRespiratoryOverlay,
                    onShowRespiratoryOverlayChange = { showRespiratoryOverlay = it },
                    showMovementOverlay = showMovementOverlay,
                    onShowMovementOverlayChange = { showMovementOverlay = it },
                    modifier = Modifier.padding(horizontal = HelionScreenEdgeMargin),
                )
            }
        }

        allNights?.let { all ->
            item(key = "averages") {
                SleepAveragesSection(
                    nights = all,
                    window = averageWindow,
                    onWindowChange = { averageWindow = it },
                    modifier = Modifier.padding(horizontal = HelionScreenEdgeMargin).padding(top = 12.dp),
                )
            }
        }

        // Every night except the one already open, newest first.
        val history = loaded.mapIndexed { index, episode -> index to episode }
            .filter { (index, _) -> openNap != null || index != selectedIndex }
            .reversed()
        if (history.isNotEmpty()) {
            item(key = "history") {
                EpisodeList(
                    title = stringResource(R.string.sleep_history_title),
                    modifier = Modifier.padding(horizontal = HelionScreenEdgeMargin).padding(top = 12.dp),
                ) {
                    history.forEachIndexed { position, (index, episode) ->
                        if (position > 0) HorizontalRule()
                        HistoryRow(episode, onClick = {
                            selectedIndex = index
                            selectedNapIndex = null
                        })
                    }
                }
            }
        }

        if (naps.isNotEmpty()) {
            item(key = "naps") {
                EpisodeList(
                    title = stringResource(R.string.sleep_naps_title),
                    modifier = Modifier.padding(horizontal = HelionScreenEdgeMargin).padding(top = 12.dp),
                ) {
                    naps.reversed().forEachIndexed { reversedIndex, episode ->
                        if (reversedIndex > 0) HorizontalRule()
                        HistoryRow(
                            episode,
                            onClick = { selectedNapIndex = naps.lastIndex - reversedIndex },
                            startTime = CLOCK_FORMAT.format(Instant.ofEpochSecond(episode.fellAsleepAt)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun longDate(episode: SleepEpisode, isNap: Boolean): String {
    val weekday = episode.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.FRENCH).replaceFirstChar { it.uppercase() }
    val month = episode.date.month.getDisplayName(TextStyle.FULL, Locale.FRENCH)
    val date = "$weekday ${episode.date.dayOfMonth} $month"
    return if (isNap) "${stringResource(R.string.sleep_nap_label)} · $date" else date
}

/** Previous/next night, as one compact control in the header. */
@Composable
private fun NightStepper(hasPrevious: Boolean, hasNext: Boolean, onPrevious: () -> Unit, onNext: () -> Unit) {
    val colors = HelionThemeTokens.colors
    Row(modifier = Modifier.helionWell(10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious, enabled = hasPrevious, modifier = Modifier.size(44.dp)) {
            Icon(
                HelionIcons.ChevronLeft,
                contentDescription = stringResource(R.string.sleep_previous_night),
                tint = if (hasPrevious) colors.textPrimary else colors.textTertiary.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
        Box(Modifier.size(width = 1.dp, height = 20.dp).helionWell(0.dp))
        IconButton(onClick = onNext, enabled = hasNext, modifier = Modifier.size(44.dp)) {
            Icon(
                HelionIcons.ChevronRight,
                contentDescription = stringResource(R.string.sleep_next_night),
                tint = if (hasNext) colors.textPrimary else colors.textTertiary.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SelectedNight(
    episode: SleepEpisode,
    baseline: Baseline?,
    showRespiratoryOverlay: Boolean,
    onShowRespiratoryOverlayChange: (Boolean) -> Unit,
    showMovementOverlay: Boolean,
    onShowMovementOverlayChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HelionThemeTokens.colors
    val phaseSource = remember(episode) { resolveSleepPhases(episode) }
    val phaseMinutes = when (phaseSource) {
        is SleepPhaseSource.Measured -> phaseSource.minutes
        is SleepPhaseSource.Estimated -> phaseSource.minutes
        SleepPhaseSource.NotEstimable -> emptyList()
    }
    // Minutes per phase across the whole night, time awake included -- the same count the
    // history bar's composition uses (see nightStageComposition), so the bar, the legend and
    // the hypnogram's shares all describe the night as it was.
    val breakdown = remember(phaseSource) { phaseMinutes.groupingBy { it.phase }.eachCount() }
    val segments = remember(phaseSource) { phaseSegments(phaseSource) }
    // One cursor shared by the hypnogram and the heart-rate chart.
    var cursor by remember(episode) { mutableStateOf<Long?>(null) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HelionCardSpacing)) {
        SummaryCard(episode, baseline, phaseSource, breakdown)

        if (phaseSource !is SleepPhaseSource.NotEstimable) {
            HelionSurface(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.sleep_hypnogram_title), style = HelionType.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
                    if (phaseSource is SleepPhaseSource.Estimated) {
                        Text(stringResource(R.string.sleep_history_estimated_tag), style = HelionType.labelSmall, color = colors.textTertiary)
                    }
                }
                Text(
                    cursor?.let { cursorReadout(episode, segments, it) } ?: stringResource(R.string.sleep_cursor_hint),
                    style = HelionType.labelSmall,
                    color = if (cursor != null) colors.textPrimary else colors.textTertiary,
                )
                Hypnogram(episode, segments, breakdown, cursor = cursor, onCursor = { cursor = it })
            }
        }

        HelionSurface(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.sleep_night_heart_rate_title), style = HelionType.title, color = colors.textPrimary)
            NightHeartRate(
                episode = episode,
                showRespiratory = showRespiratoryOverlay,
                onShowRespiratoryChange = onShowRespiratoryOverlayChange,
                showMovement = showMovementOverlay,
                onShowMovementChange = onShowMovementOverlayChange,
                cursor = cursor,
                onCursor = { cursor = it },
            )
            if (phaseSource is SleepPhaseSource.NotEstimable) {
                Text(stringResource(R.string.sleep_phase_not_estimable), style = HelionType.bodySmall, color = colors.textSecondary)
            }
        }
    }
}

/**
 * The night at a glance: total sleep as the dominant figure with the bedtime window beside
 * it, how it compares (to the owner's own nights, and to the recommended range), then the
 * phase split as one bar with its legend, and the night's awakenings and efficiency.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryCard(episode: SleepEpisode, baseline: Baseline?, phaseSource: SleepPhaseSource, breakdown: Map<SleepPhase, Int>) {
    val colors = HelionThemeTokens.colors
    val hours = episode.durationAsleepMinutes / 60
    val minutes = episode.durationAsleepMinutes % 60
    HelionSurface(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // The window sits beside the duration while both fit, and drops underneath at large
        // font scales -- the duration itself never wraps (see DurationTextWidthTest).
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.Bottom,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.sleep_total_label), style = HelionType.labelSmall, color = colors.textTertiary)
                Text(
                    stringResource(R.string.sleep_duration_format, hours.toInt(), minutes.toInt()),
                    style = SLEEP_DURATION_STYLE,
                    color = colors.textPrimary,
                    softWrap = false,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${stringResource(R.string.sleep_fell_asleep)} – ${stringResource(R.string.sleep_woke_at)}",
                    style = HelionType.labelSmall,
                    color = colors.textTertiary,
                )
                Text(
                    stringResource(
                        R.string.sleep_window_value,
                        CLOCK_FORMAT.format(Instant.ofEpochSecond(episode.fellAsleepAt)),
                        CLOCK_FORMAT.format(Instant.ofEpochSecond(episode.wokeAt)),
                    ),
                    style = HelionType.valueSmall,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }

        // What the duration means: provisional or unreliable first, then the comparisons.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (episode.isInProgress) SummaryLine(stringResource(R.string.sleep_in_progress_note), attention = true)
            if (episode.hasDataGap) SummaryLine(stringResource(R.string.sleep_data_gap_note), attention = true)
            if (episode.kind == SleepEpisodeKind.NIGHT && !episode.isInProgress && !episode.hasDataGap) {
                val hoursAsleep = episode.durationAsleepMinutes / 60.0
                val (personalRes, personalNotable) = personalBaselineMessage(placeAgainstBaseline(hoursAsleep, baseline))
                val (referenceRes, referenceNotable) = referenceMessage("sleep_duration", referenceForSleepDuration(hoursAsleep))
                SummaryLine(stringResource(personalRes), attention = personalNotable)
                SummaryLine(stringResource(referenceRes), attention = referenceNotable)
            }
        }

        if (phaseSource !is SleepPhaseSource.NotEstimable && breakdown.values.sum() > 0) {
            HorizontalRule()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.sleep_phases_title), style = HelionType.labelSmall, color = colors.textTertiary, modifier = Modifier.weight(1f))
                if (phaseSource is SleepPhaseSource.Estimated) {
                    Text(stringResource(R.string.sleep_phase_title_estimated), style = HelionType.labelSmall, color = colors.textTertiary)
                }
            }
            PhaseBar(breakdown, colors)
            PhaseLegend(breakdown)
        }

        HorizontalRule()
        HelionStatRow(
            listOf(
                StatCell(
                    stringResource(R.string.sleep_awakenings),
                    stringResource(R.string.sleep_awakenings_value, episode.awakenings, episode.awakeningsDurationMinutes),
                ),
                StatCell(stringResource(R.string.sleep_efficiency), "${(episode.sleepEfficiency * 100).toInt()} %"),
            ),
        )
    }
}

@Composable
private fun SummaryLine(text: String, attention: Boolean) {
    val colors = HelionThemeTokens.colors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusDot(if (attention) colors.attention else colors.textTertiary, modifier = Modifier.padding(top = 6.dp))
        Text(text, style = HelionType.bodySmall, color = if (attention) colors.attention else colors.textSecondary)
    }
}

/** The night's phases as one continuous bar in night order of depth: deep, light, REM, awake. */
@Composable
private fun PhaseBar(breakdown: Map<SleepPhase, Int>, colors: HelionColors) {
    val phaseColor = phaseColors(colors)
    val order = listOf(SleepPhase.DEEP, SleepPhase.LIGHT, SleepPhase.REM, SleepPhase.AWAKE)
    val total = breakdown.values.sum().coerceAtLeast(1).toFloat()
    Canvas(modifier = Modifier.fillMaxWidth().height(10.dp)) {
        val radius = CornerRadius(size.height / 2f)
        clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, radius)) }) {
            var left = 0f
            val gap = 2.dp.toPx()
            order.forEach { phase ->
                val width = (breakdown[phase] ?: 0) / total * size.width
                if (width > 0f) {
                    drawRect(phaseColor.getValue(phase), Offset(left, 0f), Size((width - gap).coerceAtLeast(1f), size.height))
                    left += width
                }
            }
        }
    }
}

/** Each phase's duration and share of the night, two to a row. */
@Composable
private fun PhaseLegend(breakdown: Map<SleepPhase, Int>) {
    val colors = HelionThemeTokens.colors
    val phaseColor = phaseColors(colors)
    val total = breakdown.values.sum().coerceAtLeast(1)
    val order = listOf(SleepPhase.DEEP, SleepPhase.LIGHT, SleepPhase.REM, SleepPhase.AWAKE)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        order.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { phase ->
                    val minutes = breakdown[phase] ?: 0
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusDot(phaseColor.getValue(phase), size = 8.dp, modifier = Modifier.padding(top = 4.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(stringResource(phaseLabelRes(phase)), style = HelionType.labelSmall, color = colors.textSecondary)
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(phaseDurationText(minutes), style = HelionType.valueSmall, color = colors.textPrimary, modifier = Modifier.alignByBaseline())
                                Text(
                                    stringResource(R.string.sleep_phase_share, (minutes * 100f / total).roundToInt()),
                                    style = HelionType.labelSmall,
                                    color = colors.textTertiary,
                                    modifier = Modifier.alignByBaseline(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A titled card of rows: the recent nights, the naps. */
@Composable
private fun EpisodeList(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HelionSectionLabel(title)
        HelionSurface(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) { content() }
    }
}

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
    return "%dh%02d".format(hours, minutes)
}

/**
 * Averages over the last few nights, with how many nights each figure actually rests on --
 * a window of seven with two usable nights says so rather than passing off two as seven.
 */
@Composable
private fun SleepAveragesSection(
    nights: List<SleepEpisode>,
    window: SleepAverageWindow,
    onWindowChange: (SleepAverageWindow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HelionThemeTokens.colors
    val averages = remember(nights, window) { computeSleepAverages(nights, window) }
    val missing = stringResource(R.string.sleep_average_value_missing)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HelionSectionLabel(stringResource(R.string.sleep_average_section_title))
        HelionSurface(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            HelionSegmentedControl(
                options = SleepAverageWindow.entries.map { stringResource(it.labelRes) },
                selectedIndex = SleepAverageWindow.entries.indexOf(window),
                onSelect = { onWindowChange(SleepAverageWindow.entries[it]) },
            )
            if (averages.consideredNights == 0) {
                Text(stringResource(R.string.sleep_average_no_nights), style = HelionType.bodySmall, color = colors.textTertiary)
                return@HelionSurface
            }
            Text(
                pluralStringResource(
                    R.plurals.sleep_average_nights_basis,
                    averages.consideredNights,
                    averages.consideredNights,
                    averages.totalNights,
                ),
                style = HelionType.labelSmall,
                color = colors.textTertiary,
            )
            HelionStatRow(
                listOf(
                    StatCell(stringResource(R.string.sleep_average_duration_label), averageDurationText(averages.avgDurationMinutes)),
                    StatCell(
                        stringResource(R.string.sleep_awakenings),
                        averages.avgAwakenings?.let {
                            stringResource(R.string.sleep_awakenings_value, it.roundToInt(), (averages.avgAwakeningsDurationMinutes ?: 0.0).roundToInt())
                        } ?: missing,
                    ),
                ),
            )
            HelionStatRow(
                listOf(
                    StatCell(stringResource(R.string.sleep_efficiency), averages.avgEfficiency?.let { "${(it * 100).toInt()} %" } ?: missing),
                    StatCell(
                        stringResource(R.string.metric_respiratory_rate),
                        averages.avgRespiratoryRate?.let { "${it.roundToInt()}" } ?: missing,
                        unit = averages.avgRespiratoryRate?.let { stringResource(R.string.unit_breaths_per_minute) },
                    ),
                ),
            )
            HorizontalRule()
            HelionStatRow(
                listOf(
                    StatCell(stringResource(R.string.sleep_phase_deep), averagePhaseDurationText(averages.avgDeepMinutes)),
                    StatCell(stringResource(R.string.sleep_phase_rem), averagePhaseDurationText(averages.avgRemMinutes)),
                    StatCell(stringResource(R.string.sleep_phase_light), averagePhaseDurationText(averages.avgLightMinutes)),
                ),
            )
            Text(
                stringResource(R.string.sleep_average_stage_basis, averages.stageNights, averages.consideredNights),
                style = HelionType.labelSmall,
                color = colors.textTertiary,
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
