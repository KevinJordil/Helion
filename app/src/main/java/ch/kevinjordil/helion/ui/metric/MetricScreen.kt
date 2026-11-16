package ch.kevinjordil.helion.ui.metric

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.ui.home.metricIcon
import ch.kevinjordil.helion.ui.quality.Baseline
import ch.kevinjordil.helion.ui.quality.PAI_WEEKLY_TARGET
import ch.kevinjordil.helion.ui.quality.PersonalBaseline
import ch.kevinjordil.helion.ui.quality.ReferenceIndicator
import ch.kevinjordil.helion.ui.quality.SPO2_RESTING_THRESHOLD
import ch.kevinjordil.helion.ui.quality.computeBaseline
import ch.kevinjordil.helion.ui.quality.personalBaselineCompactMessage
import ch.kevinjordil.helion.ui.quality.personalBaselineMessage
import ch.kevinjordil.helion.ui.quality.placeAgainstBaseline
import ch.kevinjordil.helion.ui.quality.referenceIndicatorFor
import ch.kevinjordil.helion.ui.quality.referenceMessage
import ch.kevinjordil.helion.ui.theme.AnimatedFigure
import ch.kevinjordil.helion.ui.theme.ContextTag
import ch.kevinjordil.helion.ui.theme.HelionCardSpacing
import ch.kevinjordil.helion.ui.theme.HelionContentInset
import ch.kevinjordil.helion.ui.theme.HelionScreenEdgeMargin
import ch.kevinjordil.helion.ui.theme.HelionSectionLabel
import ch.kevinjordil.helion.ui.theme.HelionSegmentedControl
import ch.kevinjordil.helion.ui.theme.HelionStatRow
import ch.kevinjordil.helion.ui.theme.HelionSurface
import ch.kevinjordil.helion.ui.theme.HelionTextButton
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionTopBar
import ch.kevinjordil.helion.ui.theme.HelionType
import ch.kevinjordil.helion.ui.theme.HorizontalRule
import ch.kevinjordil.helion.ui.theme.StatCell
import ch.kevinjordil.helion.ui.theme.StatusDot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val READOUT_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE d MMMM · HH:mm", Locale.FRENCH).withZone(ZoneId.systemDefault())
private val DAY_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH).withZone(ZoneId.systemDefault())
private val CHIP_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val CHIP_HOUR: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d · HH'h'", Locale.FRENCH).withZone(ZoneId.systemDefault())
private val CHIP_DAY: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH).withZone(ZoneId.systemDefault())

private val RANGE_OPTIONS = listOf(
    Range.DAY to R.string.range_day,
    Range.WEEK to R.string.range_week,
    Range.MONTH to R.string.range_month,
)

private val CHART_HEIGHT = 236.dp

/**
 * A metric's detail: one large, explorable chart is the centre of the screen, with the
 * figure it is reading above it and the period's statistics and the owner's own usual
 * range below. A real navigation destination (see [ch.kevinjordil.helion.ui.HelionNavHost]),
 * so the system back gesture pops it.
 *
 * Dragging across the chart replaces the figure at the top with the reading under the
 * finger; all calculation lives in [MetricReader] and [computeBaseline], this composable
 * only renders what they return.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetricScreen(
    container: AppContainer,
    metric: Metric,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HelionThemeTokens.colors
    val hue = colors.metricColor(metric)
    val reader = remember(container) { MetricReader(container.database) }
    var range by rememberSaveable { mutableStateOf(Range.DAY) }
    var uiState by remember(metric.id) { mutableStateOf<MetricUiState?>(null) }
    var loadedAt by remember(metric.id) { mutableStateOf(System.currentTimeMillis() / 1000) }
    var scrubbed by remember(metric.id, range) { mutableStateOf<Reading?>(null) }
    var viewport by remember(metric.id, range) { mutableStateOf(ChartViewport()) }
    // Whether this metric has ever had a single reading, independent of the selected range:
    // "nothing in this window" is a fine, temporary state; "this device has never sent this
    // data at all" (SpO2 on some straps) needs its own honest message.
    var everHadData by remember(metric.id) { mutableStateOf<Boolean?>(null) }
    // The personal baseline is always computed over the fixed ~30-day window, independent
    // of the selected range -- "usual" must not shift meaning with the chart's range.
    var monthBaseline by remember(metric.id) { mutableStateOf<Baseline?>(null) }

    LaunchedEffect(metric.id, range) {
        scrubbed = null
        val now = System.currentTimeMillis() / 1000
        uiState = reader.load(metric, range, now = now)
        loadedAt = now
    }

    LaunchedEffect(metric.id) {
        val now = System.currentTimeMillis() / 1000
        everHadData = reader.load(metric, Range.YEAR, now = now).latest != null
        monthBaseline = computeBaseline(reader.load(metric, Range.MONTH, now = now).readings)
    }

    val unit = stringResource(metric.unitRes)
    val state = uiState
    val isDaily = metric.source.aggregation == Aggregation.DAILY_SUM
    val stepsGoal = container.stepsGoal.value

    Column(modifier = modifier.fillMaxSize()) {
        HelionTopBar(
            title = stringResource(metric.labelRes),
            onBack = onBack,
            leadingIcon = metricIcon(metric),
            leadingTint = hue,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HelionScreenEdgeMargin)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(HelionCardSpacing),
        ) {
            // The reading: the scrubbed value while the finger is on the chart, else the latest.
            val displayed = scrubbed ?: state?.latest
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = HelionContentInset - HelionScreenEdgeMargin).padding(top = 2.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(if (scrubbed != null) R.string.metric_selected_value else R.string.metric_latest),
                    style = HelionType.labelSmall,
                    color = if (scrubbed != null) hue else colors.textTertiary,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (displayed != null) {
                        AnimatedFigure(
                            value = displayed.value,
                            format = metric::formatValue,
                            style = HelionType.hero,
                            color = colors.textPrimary,
                            modifier = Modifier.alignByBaseline(),
                        )
                        if (unit.isNotEmpty()) {
                            Text(unit, style = HelionType.label, color = colors.textSecondary, modifier = Modifier.alignByBaseline())
                        }
                    } else {
                        Text("—", style = HelionType.hero, color = colors.textTertiary, modifier = Modifier.alignByBaseline())
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    displayed?.let {
                        val formatter = if (isDaily) DAY_FORMAT else READOUT_FORMAT
                        Text(
                            formatter.format(Instant.ofEpochSecond(it.timestamp)).replaceFirstChar { c -> c.uppercase() },
                            style = HelionType.bodySmall,
                            color = colors.textSecondary,
                        )
                    }
                    val latest = state?.latest
                    if (scrubbed == null && latest != null) {
                        val (messageRes, notable) = personalBaselineCompactMessage(placeAgainstBaseline(latest.value, monthBaseline))
                        ContextTag(stringResource(messageRes), attention = notable)
                    }
                }
            }

            HelionSegmentedControl(
                options = RANGE_OPTIONS.map { stringResource(it.second) },
                selectedIndex = RANGE_OPTIONS.indexOfFirst { it.first == range },
                onSelect = { range = RANGE_OPTIONS[it].first },
            )

            HelionSurface(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    state == null -> Spacer(Modifier.height(CHART_HEIGHT))
                    state.readings.isEmpty() -> Box(Modifier.fillMaxWidth().height(CHART_HEIGHT), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(if (everHadData == false) R.string.no_data_never else R.string.no_data),
                            style = HelionType.body,
                            color = colors.textSecondary,
                        )
                    }
                    else -> {
                        val reference = chartReference(metric.id, stepsGoal, unit)
                        val usual = monthBaseline?.let { (it.median - it.spread)..(it.median + it.spread) }
                        val domainEnd = loadedAt
                        val domainStart = if (isDaily) {
                            minOf(state.chartReadings.first().timestamp, loadedAt - range.seconds)
                        } else {
                            loadedAt - range.seconds
                        }
                        val spanForFormat = range.seconds
                        DetailChart(
                            readings = state.chartReadings,
                            domainStart = domainStart,
                            domainEnd = if (isDaily) maxOf(domainEnd, state.chartReadings.last().timestamp + 86_400) else domainEnd,
                            style = if (isDaily) ChartStyle.DAILY_BARS else ChartStyle.LINE,
                            color = hue,
                            unit = unit,
                            formatValue = metric::formatValue,
                            formatTime = { t -> chipTime(t, spanForFormat, isDaily) },
                            formatAxisTime = { t, span -> formatAxisTimestamp(t, span) },
                            description = stringResource(metric.labelRes),
                            usualRange = usual,
                            reference = reference,
                            viewport = viewport,
                            onViewportChange = { viewport = it },
                            onScrub = { scrubbed = it },
                            minVisibleSeconds = if (isDaily) 4 * 86_400L else 3_600L,
                            modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT),
                        )
                        ChartLegend(
                            color = hue,
                            label = stringResource(metric.labelRes),
                            showUsual = usual != null,
                            reference = reference,
                            zoomed = !viewport.isFull,
                            onResetZoom = { viewport = ChartViewport() },
                        )
                        Text(stringResource(R.string.metric_chart_zoom_hint), style = HelionType.labelSmall, color = colors.textTertiary)
                    }
                }
            }

            state?.stats?.let { stats ->
                HelionSectionLabel(stringResource(R.string.metric_stats_title, stringResource(RANGE_OPTIONS.first { it.first == range }.second)))
                HelionSurface(modifier = Modifier.fillMaxWidth()) {
                    HelionStatRow(
                        listOf(
                            StatCell(stringResource(R.string.stat_min), metric.formatValue(stats.min), unit),
                            StatCell(stringResource(R.string.stat_average), metric.formatValue(stats.average), unit),
                            StatCell(stringResource(R.string.stat_max), metric.formatValue(stats.max), unit),
                        ),
                    )
                }
            }

            state?.latest?.let { latest ->
                HelionSectionLabel(stringResource(R.string.metric_context_title))
                ContextCard(
                    metric = metric,
                    unit = unit,
                    baseline = monthBaseline,
                    personal = placeAgainstBaseline(latest.value, monthBaseline),
                    reference = referenceIndicatorFor(metric.id, latest.value, stepsGoal),
                    hue = hue,
                )
            }

            if (metric.descriptionRes != null || metric.noteRes != null) {
                HelionSectionLabel(stringResource(R.string.metric_about_title))
                HelionSurface(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Owner-approved wording, shown in full (no maxLines -- see NoTextClippingTest).
                    metric.descriptionRes?.let { Text(stringResource(it), style = HelionType.bodySmall, color = colors.textSecondary) }
                    metric.noteRes?.let { Text(stringResource(it), style = HelionType.bodySmall, color = colors.textTertiary) }
                }
            }
        }
    }
}

/** The goal or threshold drawn across a metric's chart, for the metrics that have a real one. */
@Composable
private fun chartReference(metricId: String, stepsGoal: Int, unit: String): ChartReference? = when (metricId) {
    "steps" -> stepsGoal.takeIf { it > 0 }?.let { ChartReference(it.toDouble(), stringResource(R.string.metric_reference_target, "$it")) }
    "pai" -> ChartReference(PAI_WEEKLY_TARGET, stringResource(R.string.metric_reference_target, "%.0f".format(PAI_WEEKLY_TARGET)))
    "spo2" -> ChartReference(SPO2_RESTING_THRESHOLD, stringResource(R.string.metric_reference_threshold, "%.0f %s".format(SPO2_RESTING_THRESHOLD, unit)))
    else -> null
}

private fun chipTime(timestamp: Long, rangeSeconds: Long, daily: Boolean): String {
    val instant = Instant.ofEpochSecond(timestamp)
    return when {
        daily -> CHIP_DAY.format(instant)
        rangeSeconds <= Range.DAY.seconds -> CHIP_TIME.format(instant)
        rangeSeconds <= Range.WEEK.seconds -> CHIP_HOUR.format(instant)
        else -> CHIP_DAY.format(instant)
    }
}

/** A key for what is on the chart, plus a way back to the full span when zoomed in. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartLegend(
    color: Color,
    label: String,
    showUsual: Boolean,
    reference: ChartReference?,
    zoomed: Boolean,
    onResetZoom: () -> Unit,
) {
    val colors = HelionThemeTokens.colors
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LegendKey(label) { Box(Modifier.size(width = 14.dp, height = 2.dp).clip(CircleShape).background(color)) }
            if (showUsual) {
                LegendKey(stringResource(R.string.metric_chart_legend_usual)) {
                    Box(Modifier.size(width = 14.dp, height = 8.dp).clip(CircleShape).background(color.copy(alpha = 0.22f)))
                }
            }
            reference?.let { ref ->
                LegendKey(ref.label) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(3) { Box(Modifier.size(width = 3.dp, height = 1.5.dp).background(colors.textSecondary)) }
                    }
                }
            }
        }
        AnimatedVisibility(visible = zoomed, enter = fadeIn(), exit = fadeOut()) {
            HelionTextButton(onClick = onResetZoom) { Text(stringResource(R.string.metric_reset_zoom), style = HelionType.labelSmall) }
        }
    }
}

@Composable
private fun LegendKey(label: String, key: @Composable () -> Unit) {
    val colors = HelionThemeTokens.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        key()
        Text(label, style = HelionType.labelSmall, color = colors.textSecondary)
    }
}

/**
 * The owner's own reference points for the current value: the usual range over 30 days
 * (median ± spread, only once there are enough days of history to say), the sentence
 * placing today's value against it, and -- for the metrics that have one -- the recognised
 * reference. All of it comes from [computeBaseline] and the reference axis; nothing here is
 * an interpretation of its own.
 */
@Composable
private fun ContextCard(
    metric: Metric,
    unit: String,
    baseline: Baseline?,
    personal: PersonalBaseline,
    reference: ReferenceIndicator,
    hue: Color,
) {
    val colors = HelionThemeTokens.colors
    val (personalRes, personalNotable) = personalBaselineMessage(personal)
    val (referenceRes, referenceNotable) = referenceMessage(metric.id, reference)
    HelionSurface(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (baseline != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.metric_usual_range_title), style = HelionType.labelSmall, color = colors.textTertiary)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(
                            R.string.metric_usual_range_value,
                            metric.formatValue(baseline.median - baseline.spread),
                            metric.formatValue(baseline.median + baseline.spread),
                        ),
                        style = HelionType.valueMedium,
                        color = colors.textPrimary,
                        modifier = Modifier.alignByBaseline(),
                    )
                    if (unit.isNotEmpty()) {
                        Text(unit, style = HelionType.labelSmall, color = colors.textSecondary, modifier = Modifier.alignByBaseline())
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.metric_usual_median, metric.formatValue(baseline.median)),
                        style = HelionType.labelSmall,
                        color = colors.textTertiary,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
            HorizontalRule()
        }
        ContextLine(stringResource(personalRes), personalNotable, dot = if (personalNotable) colors.attention else hue)
        ContextLine(stringResource(referenceRes), referenceNotable, dot = if (referenceNotable) colors.attention else colors.textTertiary)
    }
}

@Composable
private fun ContextLine(text: String, notable: Boolean, dot: Color) {
    val colors = HelionThemeTokens.colors
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusDot(dot, modifier = Modifier.padding(top = 6.dp))
        Text(text, style = HelionType.bodySmall, color = if (notable) colors.attention else colors.textSecondary, modifier = Modifier.width(0.dp).weight(1f))
    }
}
