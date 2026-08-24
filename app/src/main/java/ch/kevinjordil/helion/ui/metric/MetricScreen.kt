package ch.kevinjordil.helion.ui.metric

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())

private val RANGE_OPTIONS = listOf(
    Range.DAY to R.string.range_day,
    Range.WEEK to R.string.range_week,
    Range.MONTH to R.string.range_month,
    Range.YEAR to R.string.range_year,
)

/**
 * Single screen parameterised by [metric], covering all seven catalog entries. All
 * calculation lives in [MetricReader]; this composable only renders whatever
 * [MetricUiState] it is handed.
 */
@Composable
fun MetricScreen(
    container: AppContainer,
    metric: Metric,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reader = remember(container) { MetricReader(container.database) }
    var range by rememberSaveable { mutableStateOf(Range.WEEK) }
    var uiState by remember(metric.id) { mutableStateOf<MetricUiState?>(null) }

    LaunchedEffect(metric.id, range) {
        uiState = reader.load(metric, range, now = System.currentTimeMillis() / 1000)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.action_back))
        }

        Text(stringResource(metric.labelRes), style = MaterialTheme.typography.headlineSmall)

        val state = uiState
        val latest = state?.latest
        if (latest != null) {
            Text(
                "${metric.formatValue(latest.value)} ${stringResource(metric.unitRes)}".trim(),
                style = MaterialTheme.typography.displaySmall,
            )
            Text(TIMESTAMP_FORMAT.format(Instant.ofEpochSecond(latest.timestamp)))
        }

        RangeSelector(selected = range, onSelect = { range = it })

        if (state != null && state.readings.isEmpty()) {
            Text(stringResource(R.string.no_data))
        } else if (state != null) {
            LineChart(state.readings, modifier = Modifier.fillMaxWidth())

            state.stats?.let { stats ->
                StatsRow(stats, metric)
            }

            metric.noteRes?.let { noteRes ->
                Text(stringResource(noteRes), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RangeSelector(selected: Range, onSelect: (Range) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RANGE_OPTIONS.forEach { (option, labelRes) ->
            if (option == selected) {
                Button(onClick = { onSelect(option) }) { Text(stringResource(labelRes)) }
            } else {
                OutlinedButton(onClick = { onSelect(option) }) { Text(stringResource(labelRes)) }
            }
        }
    }
}

@Composable
private fun StatsRow(stats: MetricStats, metric: Metric, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        StatItem(stringResource(R.string.stat_min), metric.formatValue(stats.min), metric)
        StatItem(stringResource(R.string.stat_max), metric.formatValue(stats.max), metric)
        StatItem(stringResource(R.string.stat_average), metric.formatValue(stats.average), metric)
    }
}

@Composable
private fun StatItem(label: String, value: String, metric: Metric, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text("$value ${stringResource(metric.unitRes)}".trim(), style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * A minimal line chart drawn on a plain Canvas, deliberately not a charting library:
 * one screen shared by all seven metrics does not justify a new dependency.
 *
 * Handles the three degenerate cases explicitly so an empty range, a single sample, or a
 * flat run of identical values never divides by zero:
 * - empty readings: draws nothing (the caller shows [R.string.no_data] instead).
 * - a single reading: drawn as a single point rather than a lineless path.
 * - identical values (zero-height range): all points sit on a flat mid-height line
 *   instead of dividing by a zero y-range.
 */
@Composable
private fun LineChart(readings: List<Reading>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier.height(160.dp)) {
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
