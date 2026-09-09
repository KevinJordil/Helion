package ch.kevinjordil.helion.ui.activity

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.drawText
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.activity.TimelineSelection
import ch.kevinjordil.helion.activity.selectionRange
import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.store.ActivityOrigin
import ch.kevinjordil.helion.store.ActivityStatus
import ch.kevinjordil.helion.store.SportType
import ch.kevinjordil.helion.ui.metric.Reading
import ch.kevinjordil.helion.ui.metric.chartYRange
import ch.kevinjordil.helion.ui.theme.HelionField
import ch.kevinjordil.helion.ui.theme.HelionFieldLabel
import androidx.compose.ui.text.rememberTextMeasurer
import ch.kevinjordil.helion.ui.metric.chartTimeMarkers
import ch.kevinjordil.helion.ui.metric.formatAxisTimestamp
import ch.kevinjordil.helion.ui.theme.HelionCardSpacing
import ch.kevinjordil.helion.ui.theme.HelionSurface
import ch.kevinjordil.helion.ui.theme.HelionSurfacePadding
import ch.kevinjordil.helion.ui.theme.HelionScreenEdgeMargin
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/** Room under the plot for the hour labels, matching the ribbon's own axis strip. */
private val AXIS_HEIGHT = 16.dp

private val TIMELINE_CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val TIMELINE_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

/**
 * The root Column's own horizontal inset, matching every other screen's own width tests:
 * [HelionScreenEdgeMargin] plus [HelionSurfacePadding] is the historical 20dp inset
 * `DayTimelineReadoutWidthTest` measures the chart card's content against.
 */

/**
 * A chosen day's heart rate and movement intensity, scrubbable by dragging out a range
 * instead of scrubbing to a single point (see [ch.kevinjordil.helion.ui.metric.scrubReading]
 * for that other gesture, and [selectionRange] for this one's pure geometry). Selecting a
 * range and tapping create turns it into a [ActivityOrigin.MANUAL] [Activity] -- the whole
 * reason this step exists before detection, since this same chart is also how the owner will
 * later judge whether an automatic detection pass found the right boundaries.
 */
@Composable
fun DayTimelineScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onActivityCreated: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HelionThemeTokens.colors
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    val reader = remember(container) { DayTimelineReader(container.database) }

    var date by rememberSaveable { mutableStateOf(LocalDate.now(zone)) }
    var dayState by remember(date) { mutableStateOf<DayTimelineState?>(null) }
    var selection by remember(date) { mutableStateOf<TimelineSelection?>(null) }
    // Starts unset -- see Activity.sport's own kdoc: even a manually-bounded activity the
    // owner creates himself should never default to a guessed sport; the create action
    // below stays disabled until he picks one explicitly.
    var sport by rememberSaveable { mutableStateOf<SportType?>(null) }
    var titleText by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(date) {
        dayState = reader.load(date)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = HelionScreenEdgeMargin, vertical = HelionCardSpacing),
        verticalArrangement = Arrangement.spacedBy(HelionCardSpacing),
    ) {
        Text(
            stringResource(R.string.action_back),
            style = HelionType.label,
            color = colors.accentViolet,
            modifier = Modifier.clickable(onClick = onBack).padding(horizontal = HelionSurfacePadding),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = HelionSurfacePadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { date = date.minusDays(1) }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.day_timeline_previous_day))
            }
            // Centred, the same way Sommeil's night navigator centres its own date: the
            // two controls are the same control and looked like two different ones.
            Text(
                TIMELINE_DATE_FORMAT.format(date),
                style = HelionType.label,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { date = date.plusDays(1) }) {
                Icon(Icons.Filled.ArrowForward, contentDescription = stringResource(R.string.day_timeline_next_day))
            }
        }

        // The chart, its readout and the clear-selection link are one idea -- the one
        // selection tool this screen offers -- so they share one card.
        val state = dayState
        if (state != null) {
            HelionSurface(
                modifier = Modifier.fillMaxWidth(),
                padding = PaddingValues(HelionSurfacePadding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.day_timeline_selection_hint), style = HelionType.bodySmall, color = colors.textSecondary)

                if (state.heartRate.isEmpty() && state.movement.isEmpty()) {
                    Text(stringResource(R.string.day_timeline_no_data), style = HelionType.body, color = colors.textSecondary)
                } else {
                    DayTimelineCanvas(
                        state = state,
                        selection = selection,
                        onSelectionChange = { selection = it },
                        heartRateColor = colors.accentViolet,
                        movementColor = colors.textSecondary,
                        axisLabelColor = colors.textTertiary,
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                    )

                    SelectionReadout(selection, zone)

                    Text(
                        stringResource(R.string.day_timeline_clear_selection),
                        style = HelionType.bodySmall,
                        color = colors.accentViolet,
                        modifier = Modifier.clickable(enabled = selection != null) { selection = null },
                    )
                }
            }
        }

        HelionSurface(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(HelionSurfacePadding)) {
            HelionFieldLabel(stringResource(R.string.sport_picker_label))
            SportPicker(selected = sport, onSelect = { sport = it }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }

        HelionSurface(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(HelionSurfacePadding)) {
            HelionField(
                label = stringResource(R.string.activity_title_label),
                value = titleText,
                onValueChange = { titleText = it },
            )
        }

        val currentSelection = selection
        Button(
            enabled = currentSelection != null && currentSelection.durationSeconds > 0 && sport != null,
            onClick = {
                val range = currentSelection ?: return@Button
                scope.launch {
                    val id = container.database.activities().upsert(
                        Activity(
                            startTimestamp = range.start,
                            endTimestamp = range.end,
                            sport = sport,
                            title = titleText.ifBlank { null },
                            notes = null,
                            origin = ActivityOrigin.MANUAL,
                            status = ActivityStatus.CONFIRMED,
                        ),
                    )
                    onActivityCreated(id)
                }
            },
            modifier = Modifier.padding(horizontal = HelionSurfacePadding),
        ) {
            Text(stringResource(R.string.day_timeline_create_action))
        }
    }
}

/**
 * Start and end share a two-column row (a clock time, "23:59" at its widest, comfortably
 * fits half the content width -- see DayTimelineReadoutWidthTest), and duration gets its own
 * full-width row below rather than a third column: a near-24h selection's "23 h 59" needs
 * more room than a three-way split leaves it (the same two-plus-one split
 * [ch.kevinjordil.helion.ui.sleep.SleepScreen]'s own duration figures already use, for the
 * same reason).
 */
@Composable
private fun SelectionReadout(selection: TimelineSelection?, zone: ZoneId, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (selection == null) {
            Text(stringResource(R.string.day_timeline_no_selection), style = HelionType.bodySmall, color = colors.textTertiary)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadoutItem(stringResource(R.string.activity_start_label), TIMELINE_CLOCK_FORMAT.format(Instant.ofEpochSecond(selection.start)), Modifier.weight(1f))
                ReadoutItem(stringResource(R.string.activity_end_label), TIMELINE_CLOCK_FORMAT.format(Instant.ofEpochSecond(selection.end)), Modifier.weight(1f))
            }
            ReadoutItem(stringResource(R.string.day_timeline_selection_duration), activityDurationText(selection.durationSeconds))
        }
    }
}

@Composable
private fun ReadoutItem(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    Column(modifier = modifier) {
        Text(label, style = HelionType.labelSmall, color = colors.textTertiary)
        Text(value, style = HelionType.valueMedium, color = colors.accentViolet)
    }
}

/**
 * Draws heart rate and movement intensity against the full day window (not just the span
 * with readings -- a quiet stretch of the day must read as an honest gap, same reasoning as
 * [ch.kevinjordil.helion.ui.ribbon.RibbonBar]'s kdoc), each on its own normalised scale like
 * [ch.kevinjordil.helion.ui.sleep.NightChartSection]'s overlays. Dragging picks out a range
 * via [selectionRange]; the selection is drawn as a translucent band with a solid edge line
 * at each boundary and persists after the finger lifts, so the readout below the chart stays
 * legible while the owner keeps adjusting or decides to create the activity.
 */
@Composable
private fun DayTimelineCanvas(
    state: DayTimelineState,
    selection: TimelineSelection?,
    onSelectionChange: (TimelineSelection?) -> Unit,
    heartRateColor: Color,
    movementColor: Color,
    axisLabelColor: Color,
    modifier: Modifier = Modifier,
) {
    var canvasWidthPx by remember { mutableStateOf(0f) }
    var anchorFraction by remember { mutableStateOf<Float?>(null) }
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = modifier
            .onSizeChanged { size: IntSize -> canvasWidthPx = size.width.toFloat() }
            .pointerInput(state) {
                detectDragGestures(
                    onDragStart = { offset ->
                        if (canvasWidthPx > 0f) {
                            val fraction = (offset.x / canvasWidthPx).coerceIn(0f, 1f)
                            anchorFraction = fraction
                            onSelectionChange(selectionRange(state.windowStart, state.windowEnd, fraction, fraction))
                        }
                    },
                    onDragEnd = { anchorFraction = null },
                    onDragCancel = { anchorFraction = null },
                ) { change, _ ->
                    val anchor = anchorFraction
                    if (canvasWidthPx > 0f && anchor != null) {
                        val fraction = (change.position.x / canvasWidthPx).coerceIn(0f, 1f)
                        onSelectionChange(selectionRange(state.windowStart, state.windowEnd, anchor, fraction))
                    }
                }
            },
    ) {
        val windowSpan = (state.windowEnd - state.windowStart).toFloat().takeIf { it > 0f } ?: return@Canvas
        // An hour axis, like the metric chart and Accueil's ribbon have: without one this
        // was the only chart in the app the owner had to guess the times on, and dragging a
        // range out of it is exactly the task that needs them.
        val axisHeightPx = AXIS_HEIGHT.toPx()
        val plotHeight = (size.height - axisHeightPx).coerceAtLeast(0f)

        fun xOf(timestamp: Long): Float = ((timestamp - state.windowStart) / windowSpan * size.width).coerceIn(0f, size.width)

        selection?.let { sel ->
            val left = xOf(sel.start)
            val right = xOf(sel.end)
            drawRect(
                color = heartRateColor.copy(alpha = 0.18f),
                topLeft = Offset(left, 0f),
                size = Size((right - left).coerceAtLeast(0f), plotHeight),
            )
            drawLine(heartRateColor, Offset(left, 0f), Offset(left, plotHeight), strokeWidth = 3f)
            drawLine(heartRateColor, Offset(right, 0f), Offset(right, plotHeight), strokeWidth = 3f)
        }

        drawSeries(state.movement, xOf = ::xOf, height = plotHeight, color = movementColor, strokeWidth = 2.5f, dashed = true)
        drawSeries(state.heartRate, xOf = ::xOf, height = plotHeight, color = heartRateColor, strokeWidth = 3.5f, dashed = false)

        var lastLabelRight = Float.NEGATIVE_INFINITY
        chartTimeMarkers(state.windowStart, state.windowEnd).forEach { timestamp ->
            val label = textMeasurer.measure(
                formatAxisTimestamp(timestamp, state.windowEnd - state.windowStart),
                HelionType.axisLabel.copy(color = axisLabelColor),
            )
            val labelLeft = (xOf(timestamp) - label.size.width / 2f)
                .coerceIn(0f, (size.width - label.size.width).coerceAtLeast(0f))
            if (labelLeft >= lastLabelRight) {
                drawText(label, topLeft = Offset(labelLeft, size.height - label.size.height))
                lastLabelRight = labelLeft + label.size.width
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeries(
    readings: List<Reading>,
    xOf: (Long) -> Float,
    height: Float,
    color: Color,
    strokeWidth: Float,
    dashed: Boolean,
) {
    if (readings.size < 2) return

    val rawMin = readings.minOf { it.value }.toFloat()
    val rawMax = readings.maxOf { it.value }.toFloat()
    val (minY, maxY) = chartYRange(rawMin, rawMax, zeroBased = false)
    val span = (maxY - minY).takeIf { it > 0f } ?: 1f
    fun yOf(v: Float) = height - (v - minY) / span * height

    val path = Path()
    readings.forEachIndexed { index, reading ->
        val x = xOf(reading.timestamp)
        val y = yOf(reading.value.toFloat())
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    val style = if (dashed) {
        Stroke(width = strokeWidth, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
    } else {
        Stroke(width = strokeWidth)
    }
    drawPath(path, color = color, style = style)
}
