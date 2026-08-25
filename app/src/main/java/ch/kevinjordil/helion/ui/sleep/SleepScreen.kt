package ch.kevinjordil.helion.ui.sleep

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import ch.kevinjordil.helion.ui.ribbon.DayRibbon
import ch.kevinjordil.helion.ui.ribbon.buildRibbon
import ch.kevinjordil.helion.ui.ribbon.heroRibbonSize
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
 * Sommeil: the most recent night first, in the same "instrument" style as Accueil's hero
 * (a large duration, not a headline), then the recent nights below so a trend is visible
 * across roughly the last month. Built entirely from [SleepReader] and
 * [segmentSleepEpisodes]; this composable only renders what it is handed.
 *
 * The two states [SleepEpisode.isInProgress] and [SleepEpisode.hasDataGap] are never
 * silently absorbed into a normal-looking number: both suppress the quality comparisons
 * (a provisional or untrustworthy duration has nothing honest to say "usual" or "in range"
 * about) and both surface their own explicit note instead.
 */
@Composable
fun SleepScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    val reader = remember(container) { SleepReader(container.database) }

    var nights by remember { mutableStateOf<List<SleepEpisode>?>(null) }
    var baseline by remember { mutableStateOf<Baseline?>(null) }

    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis() / 1000
        val loaded = reader.loadNights(now)
        nights = loaded
        // The baseline is computed only from completed, trustworthy nights: an
        // in-progress or gappy duration is not a reading Helion can vouch for, and must
        // not quietly pull the owner's own history off centre.
        val history = loaded
            .filterNot { it.isInProgress || it.hasDataGap }
            .map { Reading(it.wokeAt, it.durationAsleepMinutes / 60.0) }
        baseline = computeBaseline(history)
    }

    val loaded = nights ?: return
    val lastNight = loaded.lastOrNull()
    val history = loaded.dropLast(1).reversed()

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

        if (lastNight == null) {
            Text(stringResource(R.string.sleep_no_nights), style = HelionType.body, color = colors.textSecondary)
            return
        }

        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { LastNightCard(lastNight, baseline) }
            if (history.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.sleep_history_title).uppercase(),
                        style = HelionType.label,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(history) { episode -> HistoryRow(episode) }
            }
        }
    }
}

@Composable
private fun LastNightCard(episode: SleepEpisode, baseline: Baseline?) {
    val colors = HelionThemeTokens.colors
    val hours = episode.durationAsleepMinutes / 60
    val minutes = episode.durationAsleepMinutes % 60

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
        Text(
            "${stringResource(R.string.sleep_fell_asleep)} ${CLOCK_FORMAT.format(Instant.ofEpochSecond(episode.fellAsleepAt))} — " +
                "${stringResource(R.string.sleep_woke_at)} ${CLOCK_FORMAT.format(Instant.ofEpochSecond(episode.wokeAt))}",
            style = HelionType.bodySmall,
            color = colors.textSecondary,
        )

        if (episode.isInProgress) {
            Text(stringResource(R.string.sleep_in_progress_note), style = HelionType.bodySmall, color = colors.accentAmber)
        }
        if (episode.hasDataGap) {
            Text(stringResource(R.string.sleep_data_gap_note), style = HelionType.bodySmall, color = colors.accentAmber)
        }

        if (!episode.isInProgress && !episode.hasDataGap) {
            val (personalRes, personalAmber) = personalBaselineMessage(
                placeAgainstBaseline(episode.durationAsleepMinutes / 60.0, baseline),
            )
            val (referenceRes, referenceAmber) = referenceMessage(
                "sleep_duration",
                referenceForSleepDuration(episode.durationAsleepMinutes / 60.0),
            )
            Text(stringResource(personalRes), style = HelionType.bodySmall, color = if (personalAmber) colors.accentAmber else colors.textSecondary)
            Text(stringResource(referenceRes), style = HelionType.bodySmall, color = if (referenceAmber) colors.accentAmber else colors.textTertiary)
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            StatItem(stringResource(R.string.sleep_awakenings), stringResource(R.string.sleep_awakenings_value, episode.awakenings, episode.awakeningsDurationMinutes))
            StatItem(stringResource(R.string.sleep_efficiency), "${(episode.sleepEfficiency * 100).toInt()} %")
            StatItem(stringResource(R.string.sleep_min_heart_rate), episode.minHeartRate?.let { "$it ${stringResource(R.string.unit_bpm)}" } ?: "—")
            StatItem(
                stringResource(R.string.sleep_avg_respiratory_rate),
                episode.avgRespiratoryRate?.let { "%.0f %s".format(it, stringResource(R.string.unit_breaths_per_minute)) } ?: "—",
            )
        }
    }
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
private fun HistoryRow(episode: SleepEpisode) {
    val colors = HelionThemeTokens.colors
    val hours = episode.durationAsleepMinutes / 60
    val minutes = episode.durationAsleepMinutes % 60
    val tag = when {
        episode.isInProgress -> stringResource(R.string.sleep_history_in_progress_tag)
        episode.hasDataGap -> stringResource(R.string.sleep_history_incomplete_tag)
        else -> null
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(DATE_FORMAT.format(episode.date), style = HelionType.body, color = colors.textSecondary)
        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
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
