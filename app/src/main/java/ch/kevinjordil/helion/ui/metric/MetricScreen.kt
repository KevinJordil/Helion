package ch.kevinjordil.helion.ui.metric

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())

private val RANGE_OPTIONS = listOf(
    Range.DAY to R.string.range_day,
    Range.WEEK to R.string.range_week,
    Range.MONTH to R.string.range_month,
)

/** The detail screen's own value: smaller than Accueil's hero, still the dominant element. */
private val DETAIL_VALUE_STYLE = HelionType.hero.copy(fontSize = 56.sp, lineHeight = 60.sp)

/**
 * The metric detail destination, parameterised by [metric] -- a real navigation
 * destination, not local state, so the system back gesture works without any
 * [androidx.activity.compose.BackHandler] here (see [ch.kevinjordil.helion.ui.HelionNavHost]
 * for the one place a back handler is still needed, and why).
 *
 * The chart is scrubbable: dragging a finger across it shows the exact reading under the
 * touch instead of only ever the latest one -- this screen exists mainly for that gesture.
 * All calculation lives in [MetricReader] and [scrubReading]; this composable only renders
 * whatever [MetricUiState] it is handed.
 */
@Composable
fun MetricScreen(
    container: AppContainer,
    metric: Metric,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HelionThemeTokens.colors
    val reader = remember(container) { MetricReader(container.database) }
    var range by rememberSaveable { mutableStateOf(Range.WEEK) }
    var uiState by remember(metric.id) { mutableStateOf<MetricUiState?>(null) }
    var scrubbed by remember(metric.id, range) { mutableStateOf<Reading?>(null) }

    LaunchedEffect(metric.id, range) {
        scrubbed = null
        uiState = reader.load(metric, range, now = System.currentTimeMillis() / 1000)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.action_back),
            style = HelionType.label,
            color = colors.accentViolet,
            modifier = Modifier.clickable(onClick = onBack),
        )

        Text(
            stringResource(metric.labelRes).uppercase(),
            style = HelionType.label,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        val state = uiState
        val displayed = scrubbed ?: state?.latest
        if (displayed != null) {
            Text(
                "${metric.formatValue(displayed.value)} ${stringResource(metric.unitRes)}".trim(),
                style = DETAIL_VALUE_STYLE,
                color = colors.accentViolet,
            )
            Text(
                TIMESTAMP_FORMAT.format(Instant.ofEpochSecond(displayed.timestamp)),
                style = HelionType.bodySmall,
                color = colors.textSecondary,
            )
        }

        RangeSelector(selected = range, onSelect = { range = it })

        if (state != null && state.readings.isEmpty()) {
            Text(stringResource(R.string.no_data), style = HelionType.body, color = colors.textSecondary)
        } else if (state != null) {
            ScrubbableChart(
                readings = state.readings,
                lineColor = colors.accentViolet,
                onScrub = { scrubbed = it },
                modifier = Modifier.fillMaxWidth(),
            )

            state.stats?.let { stats ->
                StatsRow(stats, metric)
            }

            metric.noteRes?.let { noteRes ->
                Text(stringResource(noteRes), style = HelionType.bodySmall, color = colors.textTertiary)
            }
        }
    }
}

@Composable
private fun RangeSelector(selected: Range, onSelect: (Range) -> Unit, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        RANGE_OPTIONS.forEach { (option, labelRes) ->
            Text(
                stringResource(labelRes).uppercase(),
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
private fun StatsRow(stats: MetricStats, metric: Metric, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        StatItem(stringResource(R.string.stat_min), metric.formatValue(stats.min), metric, colors.textPrimary)
        StatItem(stringResource(R.string.stat_max), metric.formatValue(stats.max), metric, colors.textPrimary)
        StatItem(stringResource(R.string.stat_average), metric.formatValue(stats.average), metric, colors.accentViolet)
    }
}

@Composable
private fun StatItem(label: String, value: String, metric: Metric, valueColor: Color, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), style = HelionType.labelSmall, color = colors.textTertiary)
        Text(
            "$value ${stringResource(metric.unitRes)}".trim(),
            style = HelionType.valueMedium,
            color = valueColor,
        )
    }
}

/**
 * A minimal line chart on a plain Canvas, deliberately not a charting library: one screen
 * shared by all seven metrics does not justify a new dependency.
 *
 * Handles the same three degenerate cases the original chart handled -- unchanged, see
 * [scrubReading]'s kdoc for the equivalent handling in the scrub gesture:
 * - empty readings: draws nothing (the caller shows [R.string.no_data] instead).
 * - a single reading: drawn as a single point rather than a lineless path.
 * - identical values (zero-height range): all points sit on a flat mid-height line instead
 *   of dividing by a zero y-range.
 *
 * Adds a drag gesture: dragging anywhere over the canvas calls [onScrub] with the reading
 * closest to that horizontal position (see [scrubReading]); releasing or cancelling the
 * drag calls [onScrub] with null, which falls back to showing the latest reading again.
 */
@Composable
private fun ScrubbableChart(
    readings: List<Reading>,
    lineColor: Color,
    onScrub: (Reading?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var canvasWidthPx by remember { mutableStateOf(0f) }

    Canvas(
        modifier = modifier
            .height(180.dp)
            .onSizeChanged { size: IntSize -> canvasWidthPx = size.width.toFloat() }
            .pointerInput(readings) {
                detectDragGestures(
                    onDragEnd = { onScrub(null) },
                    onDragCancel = { onScrub(null) },
                ) { change, _ ->
                    if (canvasWidthPx > 0f) {
                        val fraction = (change.position.x / canvasWidthPx).coerceIn(0f, 1f)
                        onScrub(scrubReading(readings, fraction))
                    }
                }
            },
    ) {
        if (readings.isEmpty()) return@Canvas

        val minX = readings.first().timestamp.toFloat()
        val maxX = readings.last().timestamp.toFloat()
        val xSpan = (maxX - minX).takeIf { it > 0f }

        val minY = readings.minOf { it.value }.toFloat()
        val maxY = readings.maxOf { it.value }.toFloat()
        val ySpan = (maxY - minY).takeIf { it > 0f }

        fun xOf(t: Float): Float =
            if (xSpan == null) size.width / 2f else (t - minX) / xSpan * size.width

        fun yOf(v: Float): Float =
            if (ySpan == null) size.height / 2f else size.height - (v - minY) / ySpan * size.height

        if (readings.size == 1) {
            val r = readings.single()
            drawCircle(color = lineColor, radius = 6f, center = Offset(xOf(r.timestamp.toFloat()), yOf(r.value.toFloat())))
            return@Canvas
        }

        val path = Path()
        readings.forEachIndexed { index, reading ->
            val x = xOf(reading.timestamp.toFloat())
            val y = yOf(reading.value.toFloat())
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = lineColor, style = Stroke(width = 4f))
    }
}
