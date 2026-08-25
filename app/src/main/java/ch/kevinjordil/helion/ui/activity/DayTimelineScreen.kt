package ch.kevinjordil.helion.ui.activity

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
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
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val TIMELINE_CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val TIMELINE_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

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
    var sport by rememberSaveable { mutableStateOf(SportType.BADMINTON) }
    var titleText by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(date) {
        dayState = reader.load(date)
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

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { date = date.minusDays(1) }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.day_timeline_previous_day))
            }
            Text(
                TIMELINE_DATE_FORMAT.format(date),
                style = HelionType.label,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { date = date.plusDays(1) }) {
                Icon(Icons.Filled.ArrowForward, contentDescription = stringResource(R.string.day_timeline_next_day))
            }
        }

        Text(stringResource(R.string.day_timeline_selection_hint), style = HelionType.bodySmall, color = colors.textSecondary)

        val state = dayState
        if (state != null) {
            if (state.heartRate.isEmpty() && state.movement.isEmpty()) {
                Text(stringResource(R.string.day_timeline_no_data), style = HelionType.body, color = colors.textSecondary)
            } else {
                DayTimelineCanvas(
                    state = state,
                    selection = selection,
                    onSelectionChange = { selection = it },
                    heartRateColor = colors.accentViolet,
                    movementColor = colors.textSecondary,
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

        Text(stringResource(R.string.sport_picker_label), style = HelionType.bodySmall, color = colors.textSecondary)
        SportPicker(selected = sport, onSelect = { sport = it })

        Text(stringResource(R.string.activity_title_label), style = HelionType.bodySmall, color = colors.textSecondary)
        OutlinedTextField(
            value = titleText,
            onValueChange = { titleText = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        val currentSelection = selection
        Button(
            enabled = currentSelection != null && currentSelection.durationSeconds > 0,
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
        Text(label.uppercase(), style = HelionType.labelSmall, color = colors.textTertiary)
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
    modifier: Modifier = Modifier,
) {
    var canvasWidthPx by remember { mutableStateOf(0f) }
    var anchorFraction by remember { mutableStateOf<Float?>(null) }

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

        fun xOf(timestamp: Long): Float = ((timestamp - state.windowStart) / windowSpan * size.width).coerceIn(0f, size.width)

        selection?.let { sel ->
            val left = xOf(sel.start)
            val right = xOf(sel.end)
            drawRect(
                color = heartRateColor.copy(alpha = 0.18f),
                topLeft = Offset(left, 0f),
                size = Size((right - left).coerceAtLeast(0f), size.height),
            )
            drawLine(heartRateColor, Offset(left, 0f), Offset(left, size.height), strokeWidth = 3f)
            drawLine(heartRateColor, Offset(right, 0f), Offset(right, size.height), strokeWidth = 3f)
        }

        drawSeries(state.movement, xOf = ::xOf, height = size.height, color = movementColor, strokeWidth = 2.5f, dashed = true)
        drawSeries(state.heartRate, xOf = ::xOf, height = size.height, color = heartRateColor, strokeWidth = 3.5f, dashed = false)
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
