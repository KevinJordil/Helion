package ch.kevinjordil.helion.ui.sleep

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.ui.metric.Reading
import ch.kevinjordil.helion.ui.metric.chartYRange
import ch.kevinjordil.helion.ui.metric.scrubReading
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val NIGHT_CHART_CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/**
 * One additional series that can be overlaid on [NightChartSection]'s heart-rate line.
 * Heart rate itself is not one of these -- it is always shown and drives the chart's own
 * bpm axis; an overlay only ever draws against its *own* min..max (see [NightChartCanvas]'s
 * kdoc for why), so [dashIntervals] gives each overlay a distinct line texture rather than
 * relying on hue alone to tell it from the others or from the heart-rate line.
 */
private data class NightOverlay(
    val readings: List<Reading>,
    val color: Color,
    val dashIntervals: FloatArray,
)

/**
 * The night's heart rate as a line, with its estimated sleep phases (see
 * [estimateSleepPhases]) drawn behind it as background bands on the same time axis, so the
 * shape of the night and its stages read together -- the centrepiece of this screen. Below
 * the chart: min/average/max heart rate for the night, and checkboxes to add respiratory
 * rate and movement intensity as extra lines on the same axis.
 *
 * Omitted entirely when the episode has fewer than two heart-rate readings: a chart with
 * fewer than two points has no line to draw and nothing to scrub, matching how
 * [RespiratoryRateChart] already treats the same degenerate case.
 *
 * Heart rate, respiratory rate and movement intensity are three different units (bpm,
 * breaths/minute, an unlabelled intensity count) with no shared zero or scale -- plotting
 * them against one shared axis would silently misrepresent all but one of them. Rather than
 * a second labelled axis (which still only has room for one extra unit before it becomes as
 * unreadable as the problem it solves), every overlay is normalised to *its own* min..max
 * range and drawn as a distinct dashed line: the shape of an overlay relative to itself is
 * genuinely useful (does respiration rise when heart rate does?), its absolute value is not
 * lost (it is never shown, so nothing is misrepresented), and [R.string.sleep_overlay_scale_note]
 * says outright that the shared vertical position does not mean a shared scale. Only heart
 * rate gets an absolute readout -- the min/average/max row below the chart, and the value
 * shown in the scrub chip.
 *
 * Off by default except heart rate itself, and the toggle states are hoisted to
 * [SleepScreen] rather than remembered here, so they survive stepping to a different night.
 */
@Composable
fun NightChartSection(
    episode: SleepEpisode,
    showRespiratory: Boolean,
    onShowRespiratoryChange: (Boolean) -> Unit,
    showMovement: Boolean,
    onShowMovementChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HelionThemeTokens.colors
    val heartRateReadings = remember(episode) {
        episode.minutes.mapNotNull { minute -> minute.heartRate?.let { Reading(minute.timestamp, it.toDouble()) } }
            .sortedBy { it.timestamp }
    }
    if (heartRateReadings.size < 2) return

    val estimate = remember(episode) { estimateSleepPhases(episode.minutes) }
    val movementReadings = remember(episode) {
        episode.minutes.mapNotNull { minute -> minute.intensity?.let { Reading(minute.timestamp, it.toDouble()) } }
            .sortedBy { it.timestamp }
    }
    val respiratoryReadings = episode.respiratoryRateReadings

    val phaseColor = phaseColors(colors)
    val phaseLabel = SleepPhase.values().associateWith { phase -> stringResource(phaseLabelRes(phase)) }
    val bpmUnit = stringResource(R.string.unit_bpm)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.sleep_night_chart_title).uppercase(), style = HelionType.label, color = colors.textSecondary)

        val overlays = buildMap {
            if (showRespiratory && respiratoryReadings.size >= 2) {
                put("respiratory", NightOverlay(respiratoryReadings, colors.textPrimary, floatArrayOf(10f, 6f)))
            }
            if (showMovement && movementReadings.size >= 2) {
                put("movement", NightOverlay(movementReadings, colors.textSecondary, floatArrayOf(3f, 5f)))
            }
        }

        NightChartCanvas(
            heartRateReadings = heartRateReadings,
            estimate = estimate,
            overlays = overlays.values.toList(),
            phaseColor = phaseColor,
            lineColor = colors.accentViolet,
            phaseLabel = phaseLabel,
            bpmUnit = bpmUnit,
            modifier = Modifier.fillMaxWidth().height(160.dp),
        )

        if (respiratoryReadings.size >= 2 || movementReadings.size >= 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (respiratoryReadings.size >= 2) {
                    OverlayCheckbox(
                        label = stringResource(R.string.sleep_overlay_respiratory),
                        checked = showRespiratory,
                        onCheckedChange = onShowRespiratoryChange,
                        color = colors.textPrimary,
                    )
                }
                if (movementReadings.size >= 2) {
                    OverlayCheckbox(
                        label = stringResource(R.string.sleep_overlay_movement),
                        checked = showMovement,
                        onCheckedChange = onShowMovementChange,
                        color = colors.textSecondary,
                    )
                }
            }
        }

        val min = heartRateReadings.minOf { it.value }
        val max = heartRateReadings.maxOf { it.value }
        val average = heartRateReadings.sumOf { it.value } / heartRateReadings.size
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NightStatItem(stringResource(R.string.stat_min), "%.0f".format(min), bpmUnit, colors.textPrimary, Modifier.weight(1f))
            NightStatItem(stringResource(R.string.stat_max), "%.0f".format(max), bpmUnit, colors.textPrimary, Modifier.weight(1f))
            NightStatItem(stringResource(R.string.stat_average), "%.0f".format(average), bpmUnit, colors.accentViolet, Modifier.weight(1f))
        }

        Text(stringResource(R.string.sleep_chart_bands_note), style = HelionType.bodySmall, color = colors.textTertiary)
        if (overlays.isNotEmpty()) {
            Text(stringResource(R.string.sleep_overlay_scale_note), style = HelionType.bodySmall, color = colors.textTertiary)
        }
    }
}

@Composable
private fun OverlayCheckbox(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, color: Color) {
    val colors = HelionThemeTokens.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = color, uncheckedColor = colors.textTertiary),
        )
        Text(label, style = HelionType.bodySmall, color = colors.textSecondary)
    }
}

@Composable
private fun NightStatItem(label: String, value: String, unit: String, valueColor: Color, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    Column(modifier = modifier) {
        Text(label.uppercase(), style = HelionType.labelSmall, color = colors.textTertiary)
        Text(value, style = HelionType.valueMedium, color = valueColor)
        Text(unit, style = HelionType.labelSmall, color = colors.textTertiary)
    }
}

/**
 * The chart itself: heart rate as a line on its own bpm-scaled axis (see [chartYRange]),
 * each estimated sleep phase drawn as a full-height background band on the same time axis,
 * and any active [overlays] drawn as thin dashed lines each normalised to its own range
 * (see [NightChartSection]'s kdoc for why). Scrubbing reuses [scrubReading] against the
 * heart-rate series -- the same geometry [ch.kevinjordil.helion.ui.metric.MetricScreen]'s
 * chart already uses -- and looks up the estimated phase closest to the resolved timestamp
 * for the chip.
 */
@Composable
private fun NightChartCanvas(
    heartRateReadings: List<Reading>,
    estimate: SleepPhaseEstimate,
    overlays: List<NightOverlay>,
    phaseColor: Map<SleepPhase, Color>,
    lineColor: Color,
    phaseLabel: Map<SleepPhase, String>,
    bpmUnit: String,
    modifier: Modifier = Modifier,
) {
    val colors = HelionThemeTokens.colors
    val textMeasurer = rememberTextMeasurer()
    var canvasWidthPx by remember { mutableStateOf(0f) }
    var scrubX by remember { mutableStateOf<Float?>(null) }
    var scrubbedReading by remember { mutableStateOf<Reading?>(null) }

    val minX = heartRateReadings.first().timestamp.toFloat()
    val maxX = heartRateReadings.last().timestamp.toFloat()
    val xSpan = (maxX - minX).takeIf { it > 0f }

    val phaseSegments = remember(estimate) { phaseSegments(estimate) }

    Canvas(
        modifier = modifier
            .onSizeChanged { size: IntSize -> canvasWidthPx = size.width.toFloat() }
            .pointerInput(heartRateReadings) {
                detectDragGestures(
                    onDragEnd = { scrubX = null; scrubbedReading = null },
                    onDragCancel = { scrubX = null; scrubbedReading = null },
                ) { change, _ ->
                    if (canvasWidthPx > 0f) {
                        val x = change.position.x.coerceIn(0f, canvasWidthPx)
                        scrubX = x
                        scrubbedReading = scrubReading(heartRateReadings, x / canvasWidthPx)
                    }
                }
            },
    ) {
        if (xSpan == null) return@Canvas

        fun xOf(t: Float): Float = ((t - minX) / xSpan * size.width).coerceIn(0f, size.width)

        // Phase bands first, full-height, behind everything else.
        phaseSegments.forEach { segment ->
            val left = xOf(segment.startTimestamp.toFloat())
            val right = xOf(segment.endTimestamp.toFloat())
            if (right > left) {
                drawRect(
                    color = phaseColor.getValue(segment.phase).copy(alpha = 0.28f),
                    topLeft = Offset(left, 0f),
                    size = Size(right - left, size.height),
                )
            }
        }

        // Each active overlay, normalised to its own range, drawn thin and dashed.
        overlays.forEach { overlay ->
            val rawMin = overlay.readings.minOf { it.value }.toFloat()
            val rawMax = overlay.readings.maxOf { it.value }.toFloat()
            val (oMinY, oMaxY) = chartYRange(rawMin, rawMax, zeroBased = false)
            val oSpan = (oMaxY - oMinY).takeIf { it > 0f } ?: 1f
            fun oyOf(v: Float) = size.height - (v - oMinY) / oSpan * size.height
            val path = Path()
            overlay.readings.forEachIndexed { index, reading ->
                val x = xOf(reading.timestamp.toFloat())
                val y = oyOf(reading.value.toFloat())
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path,
                color = overlay.color,
                style = Stroke(width = 2.5f, pathEffect = PathEffect.dashPathEffect(overlay.dashIntervals)),
            )
        }

        // The heart-rate line, on top of the bands and any overlay.
        val rawMin = heartRateReadings.minOf { it.value }.toFloat()
        val rawMax = heartRateReadings.maxOf { it.value }.toFloat()
        val (minY, maxY) = chartYRange(rawMin, rawMax, zeroBased = false)
        val ySpan = (maxY - minY).takeIf { it > 0f } ?: 1f
        fun yOf(v: Float) = size.height - (v - minY) / ySpan * size.height

        val path = Path()
        heartRateReadings.forEachIndexed { index, reading ->
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

            val phaseAtInstant = phaseSegments.minByOrNull {
                minOf(abs(it.startTimestamp - markedReading.timestamp), abs(it.endTimestamp - markedReading.timestamp))
            }?.takeIf { markedReading.timestamp in it.startTimestamp..it.endTimestamp }?.phase

            val timeText = NIGHT_CHART_CLOCK_FORMAT.format(Instant.ofEpochSecond(markedReading.timestamp))
            val valueText = "${markedReading.value.toInt()} $bpmUnit"
            val phaseText = phaseAtInstant?.let { phaseLabel.getValue(it) }

            val timeLayout = textMeasurer.measure(timeText, HelionType.labelSmall.copy(color = colors.textSecondary))
            val valueLayout = textMeasurer.measure(valueText, HelionType.labelSmall.copy(color = colors.accentViolet))
            val phaseLayout = phaseText?.let { textMeasurer.measure(it, HelionType.labelSmall.copy(color = colors.textPrimary)) }

            val padding = 8f
            val lineWidths = listOfNotNull(timeLayout.size.width, valueLayout.size.width, phaseLayout?.size?.width)
            val chipWidth = (lineWidths.maxOrNull() ?: 0) + padding * 2
            val lineHeights = listOfNotNull(timeLayout.size.height, valueLayout.size.height, phaseLayout?.size?.height)
            val chipHeight = lineHeights.sum() + padding * 2

            // Clamped to stay fully inside the canvas even when scrubbing right at either
            // edge -- the same requirement and technique as MetricScreen's chart.
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
            var lineTop = chipTop + padding / 2f
            drawText(timeLayout, topLeft = Offset(chipLeft + padding, lineTop))
            lineTop += timeLayout.size.height
            drawText(valueLayout, topLeft = Offset(chipLeft + padding, lineTop))
            lineTop += valueLayout.size.height
            phaseLayout?.let { drawText(it, topLeft = Offset(chipLeft + padding, lineTop)) }
        }
    }
}

/** One contiguous run of the same estimated phase, in `[startTimestamp, endTimestamp]`. */
private data class PhaseSegment(val startTimestamp: Long, val endTimestamp: Long, val phase: SleepPhase)

/** Collapses [SleepPhaseEstimate.Estimated]'s per-minute track into contiguous runs, for [NightChartCanvas]'s bands. */
private fun phaseSegments(estimate: SleepPhaseEstimate): List<PhaseSegment> {
    if (estimate !is SleepPhaseEstimate.Estimated) return emptyList()
    val minutes = estimate.minutes.sortedBy { it.timestamp }
    if (minutes.isEmpty()) return emptyList()

    val segments = mutableListOf<PhaseSegment>()
    var runStart = minutes.first().timestamp
    var runEnd = minutes.first().timestamp
    var runPhase = minutes.first().phase

    for (minute in minutes.drop(1)) {
        if (minute.phase == runPhase && minute.timestamp - runEnd <= 60L) {
            runEnd = minute.timestamp
        } else {
            segments.add(PhaseSegment(runStart, runEnd + 60, runPhase))
            runStart = minute.timestamp
            runEnd = minute.timestamp
            runPhase = minute.phase
        }
    }
    segments.add(PhaseSegment(runStart, runEnd + 60, runPhase))
    return segments
}
