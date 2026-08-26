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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.ui.quality.Baseline
import ch.kevinjordil.helion.ui.quality.PersonalBaseline
import ch.kevinjordil.helion.ui.quality.ReferenceIndicator
import ch.kevinjordil.helion.ui.quality.computeBaseline
import ch.kevinjordil.helion.ui.quality.personalBaselineMessage
import ch.kevinjordil.helion.ui.quality.placeAgainstBaseline
import ch.kevinjordil.helion.ui.quality.referenceIndicatorFor
import ch.kevinjordil.helion.ui.quality.referenceMessage
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
    var range by rememberSaveable { mutableStateOf(Range.DAY) }
    var uiState by remember(metric.id) { mutableStateOf<MetricUiState?>(null) }
    var scrubbed by remember(metric.id, range) { mutableStateOf<Reading?>(null) }
    // Whether this metric has ever had a single reading, independent of the selected
    // range: distinguishes "nothing in this window" (a fine, temporary state) from
    // "this device has never sent this data at all" (e.g. SpO2 on this strap), which
    // needs an honest, different message rather than reading like a stuck loading state.
    var everHadData by remember(metric.id) { mutableStateOf<Boolean?>(null) }
    // The personal baseline is always computed over the fixed ~30-day window (Range.MONTH),
    // independent of the selected Jour/Semaine/Mois range -- "usual" would otherwise shift
    // meaning depending on which chart range happened to be selected.
    var monthBaseline by remember(metric.id) { mutableStateOf<Baseline?>(null) }

    LaunchedEffect(metric.id, range) {
        scrubbed = null
        uiState = reader.load(metric, range, now = System.currentTimeMillis() / 1000)
    }

    LaunchedEffect(metric.id) {
        val now = System.currentTimeMillis() / 1000
        val yearState = reader.load(metric, Range.YEAR, now = now)
        everHadData = yearState.latest != null
        monthBaseline = computeBaseline(reader.load(metric, Range.MONTH, now = now).readings)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.action_back),
            style = HelionType.label,
            color = colors.accentViolet,
            modifier = Modifier.clickable(onClick = onBack),
        )

        // No maxLines/ellipsis: this is a section header alone on its own line, so if it
        // ever does not fit -- a long label at a large system font scale -- it wraps to a
        // second line instead of clipping. See NoTextClippingTest.
        Text(
            stringResource(metric.labelRes).uppercase(),
            style = HelionType.label,
            color = colors.textSecondary,
        )

        // No maxLines/ellipsis here either: the owner-approved wording is shown in full,
        // wrapping across as many lines as it needs. See NoTextClippingTest.
        metric.descriptionRes?.let { descriptionRes ->
            Text(
                stringResource(descriptionRes),
                style = HelionType.bodySmall,
                color = colors.textSecondary,
            )
        }

        val state = uiState
        val displayed = scrubbed ?: state?.latest
        if (displayed != null) {
            // Value and unit are two separate Text composables at two different sizes,
            // not one string in one style -- exactly the pattern Accueil's hero already
            // uses (see HomeScreen's own value+unit Row). Setting the unit in the much
            // smaller `label` style rather than at the value's own 56sp is what keeps
            // even the widest composed line ("224 bpm", "99999 pas") on one line at the
            // narrowest supported width; see MetricHeaderWidthTest.
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    metric.formatValue(displayed.value),
                    style = DETAIL_VALUE_STYLE,
                    color = colors.accentViolet,
                )
                val unit = stringResource(metric.unitRes)
                if (unit.isNotEmpty()) {
                    Text(
                        unit,
                        style = HelionType.label,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            Text(
                TIMESTAMP_FORMAT.format(Instant.ofEpochSecond(displayed.timestamp)),
                style = HelionType.bodySmall,
                color = colors.textSecondary,
            )
        }

        RangeSelector(selected = range, onSelect = { range = it })

        if (state != null && state.readings.isEmpty()) {
            val emptyMessageRes = if (everHadData == false) R.string.no_data_never else R.string.no_data
            Text(stringResource(emptyMessageRes), style = HelionType.body, color = colors.textSecondary)
        } else if (state != null) {
            ScrubbableChart(
                readings = state.chartReadings,
                metric = metric,
                lineColor = colors.accentViolet,
                onScrub = { scrubbed = it },
                modifier = Modifier.fillMaxWidth(),
            )

            state.stats?.let { stats ->
                StatsRow(stats, metric)
            }

            state.latest?.let { latest ->
                QualityRow(
                    metricId = metric.id,
                    personalBaseline = placeAgainstBaseline(latest.value, monthBaseline),
                    reference = referenceIndicatorFor(metric.id, latest.value, container.stepsGoal.value),
                )
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

/**
 * Three labelled figures side by side is the layout that collided: at a narrow width with a
 * long unit ("resp/min") or a wide value ("99999"), same-size min/max/average text in a
 * plain `SpaceBetween` row has no bound on how wide each figure can grow, so neighbours ran
 * into each other. Giving each [StatItem] an equal [Modifier.weight] fixes each one to its
 * own third of the row -- they can never collide -- and [StatItem] itself puts the unit on
 * its own line below the value rather than beside it, so the widest value alone (not
 * "value unit" together) is what has to fit that third. See MetricStatsWidthTest.
 */
@Composable
private fun StatsRow(stats: MetricStats, metric: Metric, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatItem(
            stringResource(R.string.stat_min),
            metric.formatValue(stats.min),
            metric,
            colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        StatItem(
            stringResource(R.string.stat_max),
            metric.formatValue(stats.max),
            metric,
            colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        StatItem(
            stringResource(R.string.stat_average),
            metric.formatValue(stats.average),
            metric,
            colors.accentViolet,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatItem(label: String, value: String, metric: Metric, valueColor: Color, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), style = HelionType.labelSmall, color = colors.textTertiary)
        Text(value, style = HelionType.valueMedium, color = valueColor)
        val unit = stringResource(metric.unitRes)
        if (unit.isNotEmpty()) {
            Text(unit, style = HelionType.labelSmall, color = colors.textTertiary)
        }
    }
}

/**
 * The two quality axes for the currently displayed value: [personalBaseline] (always
 * present, may be "not enough history yet") and [reference] (only for the metrics that
 * genuinely have one -- see [ReferenceIndicator]'s kdoc). Both are purely descriptive text,
 * amber only when notable, never a verdict.
 */
@Composable
private fun QualityRow(
    metricId: String,
    personalBaseline: PersonalBaseline,
    reference: ReferenceIndicator,
    modifier: Modifier = Modifier,
) {
    val colors = HelionThemeTokens.colors
    val (personalRes, personalAmber) = personalBaselineMessage(personalBaseline)
    val (referenceRes, referenceAmber) = referenceMessage(metricId, reference)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(personalRes),
            style = HelionType.bodySmall,
            color = if (personalAmber) colors.accentAmber else colors.textSecondary,
        )
        Text(
            stringResource(referenceRes),
            style = HelionType.bodySmall,
            color = if (referenceAmber) colors.accentAmber else colors.textTertiary,
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
 * The Y-axis itself scales via [chartYRange]: the data's own range plus padding for every
 * metric except steps, whose zero baseline is kept because a daily total's zero is real
 * information. See [chartYRange]'s kdoc for why.
 *
 * Adds a drag gesture: dragging anywhere over the canvas calls [onScrub] with the reading
 * closest to that horizontal position (see [scrubReading]). While the drag is active, a
 * vertical guide line is drawn at the touch position, the sample it resolved to is marked
 * on the curve, and a small readout chip shows its value and timestamp -- clamped so it
 * never runs past the canvas edges when scrubbing right at the extremes. Releasing or
 * cancelling the drag clears all of that and calls [onScrub] with null, which falls back to
 * showing the latest reading again (see the caller, [MetricScreen]).
 */
@Composable
private fun ScrubbableChart(
    readings: List<Reading>,
    metric: Metric,
    lineColor: Color,
    onScrub: (Reading?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HelionThemeTokens.colors
    val textMeasurer = rememberTextMeasurer()
    val unit = stringResource(metric.unitRes)
    var canvasWidthPx by remember { mutableStateOf(0f) }
    var scrubX by remember { mutableStateOf<Float?>(null) }
    var scrubbedReading by remember { mutableStateOf<Reading?>(null) }

    Canvas(
        modifier = modifier
            .height(180.dp)
            .onSizeChanged { size: IntSize -> canvasWidthPx = size.width.toFloat() }
            .pointerInput(readings) {
                detectDragGestures(
                    onDragEnd = {
                        scrubX = null
                        scrubbedReading = null
                        onScrub(null)
                    },
                    onDragCancel = {
                        scrubX = null
                        scrubbedReading = null
                        onScrub(null)
                    },
                ) { change, _ ->
                    if (canvasWidthPx > 0f) {
                        val x = change.position.x.coerceIn(0f, canvasWidthPx)
                        val fraction = x / canvasWidthPx
                        scrubX = x
                        val reading = scrubReading(readings, fraction)
                        scrubbedReading = reading
                        onScrub(reading)
                    }
                }
            },
    ) {
        if (readings.isEmpty()) return@Canvas

        val minX = readings.first().timestamp.toFloat()
        val maxX = readings.last().timestamp.toFloat()
        val xSpan = (maxX - minX).takeIf { it > 0f }

        val rawMin = readings.minOf { it.value }.toFloat()
        val rawMax = readings.maxOf { it.value }.toFloat()
        val zeroBased = metric.source.aggregation == Aggregation.DAILY_SUM
        val (minY, maxY) = chartYRange(rawMin, rawMax, zeroBased)
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

        val markedReading = scrubbedReading
        if (scrubX != null && markedReading != null) {
            val pointX = xOf(markedReading.timestamp.toFloat())
            val pointY = yOf(markedReading.value.toFloat())

            drawLine(
                color = colors.textSecondary,
                start = Offset(pointX, 0f),
                end = Offset(pointX, size.height),
                strokeWidth = 2f,
            )
            drawCircle(color = lineColor, radius = 7f, center = Offset(pointX, pointY))
            drawCircle(color = colors.ground, radius = 3f, center = Offset(pointX, pointY))

            val valueText = "${metric.formatValue(markedReading.value)} $unit".trim()
            val timeText = TIMESTAMP_FORMAT.format(Instant.ofEpochSecond(markedReading.timestamp))
            val valueLayout = textMeasurer.measure(valueText, HelionType.labelSmall.copy(color = colors.accentViolet))
            val timeLayout = textMeasurer.measure(timeText, HelionType.labelSmall.copy(color = colors.textSecondary))

            val padding = 8f
            val chipWidth = maxOf(valueLayout.size.width, timeLayout.size.width) + padding * 2
            val chipHeight = valueLayout.size.height + timeLayout.size.height + padding * 2

            // Centre the chip on the touch, then clamp it fully inside the canvas: without
            // this, scrubbing to the first or last sample pushes the readout half off the
            // edge and clips it -- exactly the "keep it inside the chart bounds" requirement.
            val chipLeft = (pointX - chipWidth / 2f).coerceIn(0f, (size.width - chipWidth).coerceAtLeast(0f))
            val chipTop = padding

            drawRoundRect(
                color = colors.surfaceRaised,
                topLeft = Offset(chipLeft, chipTop),
                size = Size(chipWidth, chipHeight),
                cornerRadius = CornerRadius(6f, 6f),
            )
            drawRoundRect(
                color = colors.divider,
                topLeft = Offset(chipLeft, chipTop),
                size = Size(chipWidth, chipHeight),
                cornerRadius = CornerRadius(6f, 6f),
                style = Stroke(width = 1f),
            )
            drawText(valueLayout, topLeft = Offset(chipLeft + padding, chipTop + padding / 2f))
            drawText(timeLayout, topLeft = Offset(chipLeft + padding, chipTop + padding / 2f + valueLayout.size.height))
        }
    }
}
