package ch.kevinjordil.helion.ui.sleep

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.ui.metric.Reading
import ch.kevinjordil.helion.ui.metric.chartYRange
import ch.kevinjordil.helion.ui.metric.scrubReading
import ch.kevinjordil.helion.ui.ribbon.buildCategoryRibbon
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val NIGHT_CHART_CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** Height of the heart-rate panel -- unchanged from the chart's previous single-panel height. */
private val HEART_RATE_PANEL_HEIGHT = 160.dp

/** Height of one hypnogram lane row, matched to the label next to it. */
private val HYPNOGRAM_LANE_HEIGHT = 22.dp

/**
 * Width of the label column that sits to the left of every row in the merged figure --
 * the heart-rate panel's row gets a blank spacer of this same width, each hypnogram lane
 * gets its stage label in it. Sharing one fixed width across every row is what actually
 * makes the two panels' time axes line up: both canvases are laid out as "this fixed
 * column, then the rest of the row", so both get the exact same remaining width from the
 * same parent, with no manual pixel offset to keep in sync.
 *
 * 90dp: verified to fit the widest of the four stage labels ("Paradoxal") at
 * [HelionType.labelSmall] and a 1.3x accessibility font scale -- see
 * `HypnogramLaneLabelWidthTest`.
 */
private val LANE_LABEL_WIDTH: Dp = 90.dp

/** Buckets an episode's own span into roughly ten-minute slices, clamped to a sane range for very short or very long episodes. */
private const val EPISODE_BUCKET_MINUTES = 10
private const val MIN_EPISODE_BUCKETS = 24
private const val MAX_EPISODE_BUCKETS = 96

internal fun episodeBucketCount(episode: SleepEpisode): Int {
    val spanMinutes = (episode.wokeAt - episode.fellAsleepAt) / 60 + 1
    return (spanMinutes / EPISODE_BUCKET_MINUTES).toInt().coerceIn(MIN_EPISODE_BUCKETS, MAX_EPISODE_BUCKETS)
}

/**
 * One additional series that can be overlaid on [NightChartSection]'s heart-rate line.
 * Heart rate itself is not one of these -- it is always shown and drives the chart's own
 * bpm axis; an overlay only ever draws against its *own* min..max (see the heart-rate
 * canvas's kdoc for why), so [dashIntervals] gives each overlay a distinct line texture
 * rather than relying on hue alone to tell it from the others or from the heart-rate line.
 */
private data class NightOverlay(
    val readings: List<Reading>,
    val color: Color,
    val dashIntervals: FloatArray,
)

/**
 * The night's heart rate and its sleep stages (see [resolveSleepPhases] -- measured when
 * the device's own hypnogram is available, estimated otherwise) as one figure, two stacked
 * panels sharing a single time axis: heart rate on top over a neutral background, the
 * hypnogram lanes directly below it, one scrub cursor spanning both. This replaces an
 * earlier design that encoded the same stages twice -- once as a four-lane hypnogram
 * above the chart (position, read well) and again as full-height bands tinted along one
 * violet ramp behind the heart-rate line (hue alone, at low alpha, read poorly next to
 * itself). Only the hypnogram survives: a stage switching lanes is a vertical jump, which
 * degrades gracefully in sunlight, for colour-vision deficiency and in a grayscale capture
 * -- a property no single-hue wash can offer. Below the two panels: min/average/max heart
 * rate for the night, and checkboxes to add respiratory rate and movement intensity as
 * extra lines on the heart-rate panel.
 *
 * Omitted entirely when the episode has fewer than two heart-rate readings: a chart with
 * fewer than two points has no line to draw and nothing to scrub. The hypnogram lanes are
 * themselves omitted (in favour of [R.string.sleep_phase_not_estimable]) whenever
 * [resolveSleepPhases] could not classify the night at all.
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
 *
 * The drag gesture that drives the shared scrub cursor is attached to the heart-rate panel
 * only, exactly where it was before this panel gained a hypnogram beneath it: both panels
 * read the same hoisted scrub state, so dragging over the heart-rate curve moves a cursor
 * that spans both, which is what "one scrub cursor" is actually for -- seeing the stage and
 * the heart rate at the same instant, not making every pixel of the figure draggable.
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

    val phaseSource = remember(episode) { resolveSleepPhases(episode) }
    val movementReadings = remember(episode) {
        episode.minutes.mapNotNull { minute -> minute.intensity?.let { Reading(minute.timestamp, it.toDouble()) } }
            .sortedBy { it.timestamp }
    }
    val respiratoryReadings = episode.respiratoryRateReadings

    val phaseColor = phaseColors(colors)
    val phaseLabel = SleepPhase.values().associateWith { phase -> stringResource(phaseLabelRes(phase)) }
    val bpmUnit = stringResource(R.string.unit_bpm)

    val lanes = listOf(SleepPhase.AWAKE, SleepPhase.REM, SleepPhase.LIGHT, SleepPhase.DEEP)
    val phaseSegments = remember(phaseSource) { phaseSegments(phaseSource) }
    val hypnogramBars = remember(phaseSource, episode) {
        val minutes = when (phaseSource) {
            is SleepPhaseSource.Measured -> phaseSource.minutes
            is SleepPhaseSource.Estimated -> phaseSource.minutes
            SleepPhaseSource.NotEstimable -> emptyList()
        }
        buildCategoryRibbon(
            items = minutes.map { it.timestamp to it.phase },
            windowStart = episode.fellAsleepAt,
            windowEnd = episode.wokeAt + 60,
            bucketCount = episodeBucketCount(episode),
        )
    }
    val showHypnogram = phaseSource !is SleepPhaseSource.NotEstimable

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

        var scrubFraction by remember { mutableStateOf<Float?>(null) }
        var scrubbedReading by remember { mutableStateOf<Reading?>(null) }

        if (showHypnogram) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.width(LANE_LABEL_WIDTH))
                HeartRateCanvas(
                    heartRateReadings = heartRateReadings,
                    overlays = overlays.values.toList(),
                    phaseSegments = phaseSegments,
                    lineColor = colors.accentViolet,
                    phaseLabel = phaseLabel,
                    bpmUnit = bpmUnit,
                    scrubFraction = scrubFraction,
                    scrubbedReading = scrubbedReading,
                    onScrub = { fraction, reading -> scrubFraction = fraction; scrubbedReading = reading },
                    onScrubEnd = { scrubFraction = null; scrubbedReading = null },
                    modifier = Modifier.weight(1f).height(HEART_RATE_PANEL_HEIGHT),
                )
            }
            lanes.forEach { lane ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.width(LANE_LABEL_WIDTH)) {
                        Text(phaseLabel.getValue(lane).uppercase(), style = HelionType.labelSmall, color = colors.textSecondary)
                    }
                    HypnogramLaneCanvas(
                        bars = hypnogramBars,
                        lane = lane,
                        laneColor = phaseColor.getValue(lane),
                        cursorColor = colors.textSecondary,
                        scrubFraction = scrubFraction,
                        modifier = Modifier.weight(1f).height(HYPNOGRAM_LANE_HEIGHT),
                    )
                }
            }
        } else {
            HeartRateCanvas(
                heartRateReadings = heartRateReadings,
                overlays = overlays.values.toList(),
                phaseSegments = phaseSegments,
                lineColor = colors.accentViolet,
                phaseLabel = phaseLabel,
                bpmUnit = bpmUnit,
                scrubFraction = scrubFraction,
                scrubbedReading = scrubbedReading,
                onScrub = { fraction, reading -> scrubFraction = fraction; scrubbedReading = reading },
                onScrubEnd = { scrubFraction = null; scrubbedReading = null },
                modifier = Modifier.fillMaxWidth().height(HEART_RATE_PANEL_HEIGHT),
            )
            Text(stringResource(R.string.sleep_phase_not_estimable), style = HelionType.bodySmall, color = colors.textSecondary)
        }

        if (phaseSource is SleepPhaseSource.Estimated) {
            Text(stringResource(R.string.sleep_phase_title_estimated).uppercase(), style = HelionType.labelSmall, color = colors.textTertiary)
        }

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
 * The heart-rate panel: the line itself on its own bpm-scaled axis (see [chartYRange]),
 * over a plain, neutral background -- no phase bands here any more, since the hypnogram
 * below already encodes stage by lane position, which reads better than a background wash
 * ever did and does not need to fight the line for the same pixels. Any active [overlays]
 * are drawn as thin dashed lines each normalised to its own range (see
 * [NightChartSection]'s kdoc for why). Scrubbing reuses [scrubReading] against the
 * heart-rate series -- the same geometry [ch.kevinjordil.helion.ui.metric.MetricScreen]'s
 * chart already uses -- and looks up the phase (measured or estimated) closest to the
 * resolved timestamp for the chip, via [phaseSegments].
 *
 * [scrubFraction]/[scrubbedReading] are hoisted by the caller (not remembered here) so the
 * hypnogram lanes below can draw the same cursor at the same horizontal position.
 */
@Composable
private fun HeartRateCanvas(
    heartRateReadings: List<Reading>,
    overlays: List<NightOverlay>,
    phaseSegments: List<PhaseSegment>,
    lineColor: Color,
    phaseLabel: Map<SleepPhase, String>,
    bpmUnit: String,
    scrubFraction: Float?,
    scrubbedReading: Reading?,
    onScrub: (Float, Reading?) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HelionThemeTokens.colors
    val textMeasurer = rememberTextMeasurer()
    var canvasWidthPx by remember { mutableStateOf(0f) }

    val minX = heartRateReadings.first().timestamp.toFloat()
    val maxX = heartRateReadings.last().timestamp.toFloat()
    val xSpan = (maxX - minX).takeIf { it > 0f }

    Canvas(
        modifier = modifier
            .onSizeChanged { size: IntSize -> canvasWidthPx = size.width.toFloat() }
            .pointerInput(heartRateReadings) {
                detectDragGestures(
                    onDragEnd = onScrubEnd,
                    onDragCancel = onScrubEnd,
                ) { change, _ ->
                    if (canvasWidthPx > 0f) {
                        val x = change.position.x.coerceIn(0f, canvasWidthPx)
                        onScrub(x / canvasWidthPx, scrubReading(heartRateReadings, x / canvasWidthPx))
                    }
                }
            },
    ) {
        if (xSpan == null) return@Canvas

        fun xOf(t: Float): Float = ((t - minX) / xSpan * size.width).coerceIn(0f, size.width)

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

        // The heart-rate line, on top of any overlay.
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

        if (scrubFraction != null && scrubbedReading != null) {
            val pointX = xOf(scrubbedReading.timestamp.toFloat())
            val pointY = yOf(scrubbedReading.value.toFloat())

            drawLine(
                color = colors.textSecondary,
                start = Offset(pointX, 0f),
                end = Offset(pointX, size.height),
                strokeWidth = 2f,
            )
            drawCircle(color = lineColor, radius = 7f, center = Offset(pointX, pointY))
            drawCircle(color = colors.ground, radius = 3f, center = Offset(pointX, pointY))

            val phaseAtInstant = phaseSegments.minByOrNull {
                minOf(abs(it.startTimestamp - scrubbedReading.timestamp), abs(it.endTimestamp - scrubbedReading.timestamp))
            }?.takeIf { scrubbedReading.timestamp in it.startTimestamp..it.endTimestamp }?.phase

            val timeText = NIGHT_CHART_CLOCK_FORMAT.format(Instant.ofEpochSecond(scrubbedReading.timestamp))
            val valueText = "${scrubbedReading.value.toInt()} $bpmUnit"
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

/**
 * One hypnogram lane: [lane]'s own occupied buckets from [bars] (see [buildCategoryRibbon]),
 * drawn as short vertical marks the same way [ch.kevinjordil.helion.ui.ribbon.DayRibbon]
 * draws a day's ribbon, plus the same shared scrub cursor [HeartRateCanvas] draws, at the
 * same [scrubFraction] -- since both canvases receive the same remaining width from an
 * identical fixed-width label column (see [LANE_LABEL_WIDTH]), `scrubFraction * size.width`
 * lands at the same instant in both panels without either one needing to know the other's
 * pixel size directly.
 */
@Composable
private fun HypnogramLaneCanvas(
    bars: List<Pair<Float, SleepPhase>>,
    lane: SleepPhase,
    laneColor: Color,
    cursorColor: Color,
    scrubFraction: Float?,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (bars.isNotEmpty()) {
            val strokeWidth = (size.width / 96f).coerceIn(1.5f, 4f)
            bars.forEach { (xFraction, category) ->
                if (category != lane) return@forEach
                val x = xFraction * size.width
                drawLine(
                    color = laneColor,
                    start = Offset(x, size.height),
                    end = Offset(x, 0f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
        if (scrubFraction != null) {
            val x = scrubFraction * size.width
            drawLine(color = cursorColor, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = 2f)
        }
    }
}

/** One contiguous run of the same phase (measured or estimated), in `[startTimestamp, endTimestamp]`. */
internal data class PhaseSegment(val startTimestamp: Long, val endTimestamp: Long, val phase: SleepPhase)

/** Collapses [SleepPhaseSource]'s per-minute track (measured or estimated alike) into contiguous runs, for [HeartRateCanvas]'s scrub-chip stage lookup. Empty for [SleepPhaseSource.NotEstimable]. */
internal fun phaseSegments(source: SleepPhaseSource): List<PhaseSegment> {
    val minutes = when (source) {
        is SleepPhaseSource.Measured -> source.minutes
        is SleepPhaseSource.Estimated -> source.minutes
        SleepPhaseSource.NotEstimable -> return emptyList()
    }.sortedBy { it.timestamp }
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
