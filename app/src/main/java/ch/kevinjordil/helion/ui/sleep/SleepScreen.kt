package ch.kevinjordil.helion.ui.sleep

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.source.SleepStage
import ch.kevinjordil.helion.ui.metric.Reading
import ch.kevinjordil.helion.ui.quality.Baseline
import ch.kevinjordil.helion.ui.quality.computeBaseline
import ch.kevinjordil.helion.ui.quality.personalBaselineMessage
import ch.kevinjordil.helion.ui.quality.placeAgainstBaseline
import ch.kevinjordil.helion.ui.quality.referenceForSleepDuration
import ch.kevinjordil.helion.ui.quality.referenceMessage
import ch.kevinjordil.helion.ui.ribbon.ColorBar
import ch.kevinjordil.helion.ui.ribbon.DayRibbon
import ch.kevinjordil.helion.ui.ribbon.PhaseRibbon
import ch.kevinjordil.helion.ui.ribbon.buildCategoryRibbon
import ch.kevinjordil.helion.ui.ribbon.buildRibbon
import ch.kevinjordil.helion.ui.ribbon.heroRibbonSize
import ch.kevinjordil.helion.ui.ribbon.phaseRibbonSize
import ch.kevinjordil.helion.ui.theme.HelionColors
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")

/** Buckets an episode's own span into roughly ten-minute slices, clamped to a sane range for very short or very long episodes. */
private const val EPISODE_BUCKET_MINUTES = 10
private const val MIN_EPISODE_BUCKETS = 24
private const val MAX_EPISODE_BUCKETS = 96

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
        Text(
            text = stringResource(R.string.tab_sleep).uppercase(),
            style = HelionType.label,
            color = colors.textSecondary,
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

        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                SelectedNightCard(
                    episode = selected,
                    baseline = baseline,
                    hasPrevious = selectedIndex > 0,
                    hasNext = selectedIndex < loaded.lastIndex,
                    onPrevious = { selectedIndex -= 1 },
                    onNext = { selectedIndex += 1 },
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
) {
    val colors = HelionThemeTokens.colors
    val hours = episode.durationAsleepMinutes / 60
    val minutes = episode.durationAsleepMinutes % 60

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
            Text(DATE_FORMAT.format(episode.date), style = HelionType.label, color = colors.textSecondary)
            IconButton(onClick = onNext, enabled = hasNext) {
                Icon(
                    Icons.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.sleep_next_night),
                    tint = if (hasNext) colors.textSecondary else colors.textTertiary,
                )
            }
        }

        DayRibbon(
            bars = buildRibbon(
                sleepEpisodeReadings(episode),
                windowStart = episode.fellAsleepAt,
                windowEnd = episode.wokeAt + 60,
                bucketCount = episodeBucketCount(episode),
            ),
            barColor = colors.accentViolet,
            modifier = Modifier.heroRibbonSize(),
        )

        Text(
            stringResource(R.string.sleep_duration_format, hours.toInt(), minutes.toInt()),
            style = HelionType.hero,
            color = colors.accentViolet,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(32.dp), modifier = Modifier.padding(top = 4.dp)) {
            StatItem(stringResource(R.string.sleep_fell_asleep), CLOCK_FORMAT.format(Instant.ofEpochSecond(episode.fellAsleepAt)))
            StatItem(stringResource(R.string.sleep_woke_at), CLOCK_FORMAT.format(Instant.ofEpochSecond(episode.wokeAt)))
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

        // Two rows of two, not one row of four: four instrument-style stat pairs (a short
        // uppercase label above a mono value) crowded into a single SpaceBetween row leaves
        // too little width for the longer ones ("12 · 24 min") and forces an ugly mid-value
        // wrap. Halving the row width per item is what actually gives the numbers room.
        Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem(stringResource(R.string.sleep_awakenings), stringResource(R.string.sleep_awakenings_value, episode.awakenings, episode.awakeningsDurationMinutes))
                StatItem(stringResource(R.string.sleep_efficiency), "${(episode.sleepEfficiency * 100).toInt()} %")
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem(stringResource(R.string.sleep_min_heart_rate), episode.minHeartRate?.let { "$it ${stringResource(R.string.unit_bpm)}" } ?: "—")
                StatItem(
                    stringResource(R.string.sleep_avg_respiratory_rate),
                    episode.avgRespiratoryRate?.let { "%.0f %s".format(it, stringResource(R.string.unit_breaths_per_minute)) } ?: "—",
                )
            }
        }

        SleepPhaseSection(episode)
    }
}

/**
 * Estimated phase breakdown, ribbon and legend for [episode] -- see [estimateSleepPhases].
 * Both the section title and the not-estimable fallback spell out "estimé" in the string
 * itself: this is the one place Helion shows something it did not measure, and that must
 * never read as a plain fact next to the numbers it did measure.
 */
@Composable
private fun SleepPhaseSection(episode: SleepEpisode) {
    val colors = HelionThemeTokens.colors
    val estimate = remember(episode) { estimateSleepPhases(episode.minutes) }

    Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.sleep_phase_title).uppercase(), style = HelionType.label, color = colors.textSecondary)

        when (estimate) {
            is SleepPhaseEstimate.NotEstimable ->
                Text(stringResource(R.string.sleep_phase_not_estimable), style = HelionType.bodySmall, color = colors.textSecondary)

            is SleepPhaseEstimate.Estimated -> {
                val phaseColor = phaseColors(colors)
                PhaseRibbon(
                    bars = buildCategoryRibbon(
                        items = estimate.minutes.map { it.timestamp to it.phase },
                        windowStart = episode.fellAsleepAt,
                        windowEnd = episode.wokeAt + 60,
                        bucketCount = episodeBucketCount(episode),
                    ).map { (x, phase) -> ColorBar(x, phaseColor.getValue(phase)) },
                    modifier = Modifier.phaseRibbonSize(),
                )

                PhaseLegend(colors, phaseColor)

                val breakdown = sleepPhaseBreakdown(estimate.minutes)
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatItem(stringResource(R.string.sleep_phase_deep), phaseDurationText(breakdown[SleepPhase.DEEP] ?: 0))
                    StatItem(stringResource(R.string.sleep_phase_rem), phaseDurationText(breakdown[SleepPhase.REM] ?: 0))
                    StatItem(stringResource(R.string.sleep_phase_light), phaseDurationText(breakdown[SleepPhase.LIGHT] ?: 0))
                }
            }
        }
    }
}

/**
 * What each phase colour on the ribbon and the legend means -- see [HelionColors]'s kdoc on
 * [HelionColors.phaseAwake]/[HelionColors.phaseLight]/[HelionColors.phaseRem]/
 * [HelionColors.phaseDeep] for why these four exist and are not [HelionColors.accentViolet]
 * or [HelionColors.accentAmber].
 */
private fun phaseColors(colors: HelionColors) = mapOf(
    SleepPhase.AWAKE to colors.phaseAwake,
    SleepPhase.LIGHT to colors.phaseLight,
    SleepPhase.REM to colors.phaseRem,
    SleepPhase.DEEP to colors.phaseDeep,
)

/**
 * The colour key: a swatch beside each phase's own short uppercase label, in the same order
 * as the stat row below it (deep, REM, light) with awake appended -- so colour is never the
 * only thing telling deep from REM from light from awake apart, which also keeps this
 * legible under colour-vision deficiency and in a grayscale capture.
 */
@Composable
private fun PhaseLegend(colors: HelionColors, phaseColor: Map<SleepPhase, androidx.compose.ui.graphics.Color>) {
    val entries = listOf(
        SleepPhase.DEEP to R.string.sleep_phase_deep,
        SleepPhase.REM to R.string.sleep_phase_rem,
        SleepPhase.LIGHT to R.string.sleep_phase_light,
        SleepPhase.AWAKE to R.string.sleep_phase_awake,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.forEach { (phase, labelRes) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(phaseColor.getValue(phase), CircleShape),
                )
                Text(stringResource(labelRes).uppercase(), style = HelionType.labelSmall, color = colors.textSecondary)
            }
        }
    }
}

private fun phaseDurationText(minutesInPhase: Int): String {
    val hours = minutesInPhase / 60
    val minutes = minutesInPhase % 60
    return if (hours > 0) "%d h %02d".format(hours, minutes) else "%d min".format(minutes)
}

@Composable
private fun StatItem(label: String, value: String) {
    val colors = HelionThemeTokens.colors
    Column {
        Text(label.uppercase(), style = HelionType.labelSmall, color = colors.textTertiary)
        Text(value, style = HelionType.valueMedium, color = colors.textPrimary)
    }
}

@Composable
private fun HistoryRow(episode: SleepEpisode, onClick: () -> Unit) {
    val colors = HelionThemeTokens.colors
    val hours = episode.durationAsleepMinutes / 60
    val minutes = episode.durationAsleepMinutes % 60
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
        Text(DATE_FORMAT.format(episode.date), style = HelionType.body, color = colors.textSecondary)
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

/** [episode]'s own minutes as a binary asleep/awake reading, for [buildRibbon]'s bucketing. */
private fun sleepEpisodeReadings(episode: SleepEpisode): List<Reading> =
    episode.minutes.map { Reading(it.timestamp, if (it.sleepStage == SleepStage.ASLEEP) 1.0 else 0.0) }

private fun episodeBucketCount(episode: SleepEpisode): Int {
    val spanMinutes = (episode.wokeAt - episode.fellAsleepAt) / 60 + 1
    return (spanMinutes / EPISODE_BUCKET_MINUTES).toInt().coerceIn(MIN_EPISODE_BUCKETS, MAX_EPISODE_BUCKETS)
}
