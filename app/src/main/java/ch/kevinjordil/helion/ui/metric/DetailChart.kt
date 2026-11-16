package ch.kevinjordil.helion.ui.metric

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.ui.theme.CHART_LINE_WIDTH
import ch.kevinjordil.helion.ui.theme.HelionMotion
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import ch.kevinjordil.helion.ui.theme.rememberReducedMotion
import kotlin.math.abs

/** How a [DetailChart] draws its readings. */
enum class ChartStyle {
    /** A continuous measurement: a line, broken wherever nothing was measured. */
    LINE,

    /** One total per day: a bar per day, from zero. */
    DAILY_BARS,
}

/** A horizontal line with a real meaning: a goal, a recognised threshold. */
data class ChartReference(val value: Double, val label: String)

/**
 * A secondary series drawn over the main one on its own scale (respiration or movement over
 * a night's heart rate): dashed, so it never reads as the chart's own line, and stretched to
 * the plot's height, since its units are not the axis' units.
 */
data class ChartOverlay(val readings: List<Reading>, val color: Color, val dash: FloatArray)

/** The visible part of the chart's time domain, as fractions of it. */
data class ChartViewport(val start: Float = 0f, val end: Float = 1f) {
    val isFull: Boolean get() = start <= 0.0001f && end >= 0.9999f
    val width: Float get() = end - start

    /**
     * Zooms by [factor] (above 1 zooms in) around [anchor], a 0..1 position across what is
     * currently visible, then pans by [panFraction] of the visible width. Never narrower
     * than [minWidth] of the domain, never outside it.
     */
    fun transformed(factor: Float, anchor: Float, panFraction: Float, minWidth: Float): ChartViewport {
        val newWidth = (width / factor).coerceIn(minWidth.coerceAtMost(1f), 1f)
        val pivot = start + anchor * width
        var newStart = pivot - anchor * newWidth - panFraction * newWidth
        newStart = newStart.coerceIn(0f, 1f - newWidth)
        return ChartViewport(newStart, newStart + newWidth)
    }
}

/**
 * Where the line breaks: a gap between two readings wider than this is drawn as a gap,
 * never bridged. Six times the series' usual spacing, and never less than 15 minutes, so a
 * per-minute series breaks when the strap was off and an hourly or daily one does not break
 * between its ordinary samples.
 */
internal fun lineGapThreshold(readings: List<Reading>): Long {
    if (readings.size < 3) return Long.MAX_VALUE
    val deltas = readings.zipWithNext { a, b -> b.timestamp - a.timestamp }.filter { it > 0 }.sorted()
    if (deltas.isEmpty()) return Long.MAX_VALUE
    return maxOf(deltas[deltas.size / 2] * 6, 15 * 60L)
}

/**
 * The detail screens' chart: large, technical and explorable.
 *
 * - Value gridlines on round numbers, labelled on the right edge with the unit; time ticks
 *   along the bottom on hour, day or date boundaries depending on the span shown.
 * - The owner's usual range (median ± spread over the last 30 days), when there is enough
 *   history for one, as a quiet band behind the data; a [reference] (a goal, a threshold) as
 *   a dashed line with its label.
 * - Drag horizontally to read the exact value under the finger: a cursor, a marker and a
 *   readout follow it, and [onScrub] reports the reading so the screen's own figure can
 *   follow too. The cursor stays where it was released; a tap clears it.
 * - Pinch to zoom in time and pan with two fingers; double tap returns to the full span.
 *   The value axis rescales to what is visible, so a zoomed-in detail is never flattened.
 *
 * [domainStart]/[domainEnd] are the window the chart covers, which is the selected range,
 * not just the span of the readings: an empty stretch at the start of a day is shown as
 * empty, not stretched away.
 */
@Composable
fun DetailChart(
    readings: List<Reading>,
    domainStart: Long,
    domainEnd: Long,
    style: ChartStyle,
    color: Color,
    unit: String,
    formatValue: (Double) -> String,
    formatTime: (Long) -> String,
    formatAxisTime: (timestamp: Long, spanSeconds: Long) -> String,
    description: String,
    modifier: Modifier = Modifier,
    zeroBased: Boolean = false,
    usualRange: ClosedFloatingPointRange<Double>? = null,
    reference: ChartReference? = null,
    viewport: ChartViewport = ChartViewport(),
    onViewportChange: (ChartViewport) -> Unit = {},
    onScrub: (Reading?) -> Unit = {},
    minVisibleSeconds: Long = 3_600L,
    overlays: List<ChartOverlay> = emptyList(),
    externalCursor: Long? = null,
    showReadout: Boolean = true,
) {
    val colors = HelionThemeTokens.colors
    val textMeasurer = rememberTextMeasurer()
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val reduced = rememberReducedMotion()
    var scrubX by remember(readings) { mutableStateOf<Float?>(null) }
    var plotWidthPx by remember { mutableFloatStateOf(0f) }
    val reveal = remember(readings) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(readings) {
        if (!reduced) reveal.animateTo(1f, tween(HelionMotion.LONG, easing = HelionMotion.Emphasized))
    }

    val span = (domainEnd - domainStart).coerceAtLeast(1L)
    val visibleStart = domainStart + (viewport.start * span).toLong()
    val visibleEnd = domainStart + (viewport.end * span).toLong()
    val minWidth = (minVisibleSeconds.toFloat() / span).coerceIn(0.01f, 1f)
    val gap = remember(readings) { lineGapThreshold(readings) }
    val currentViewport by androidx.compose.runtime.rememberUpdatedState(viewport)

    fun readingAt(x: Float): Reading? {
        if (plotWidthPx <= 0f) return null
        val t = visibleStart + (x / plotWidthPx).coerceIn(0f, 1f) * (visibleEnd - visibleStart)
        val visible = readings.filter { it.timestamp in visibleStart..visibleEnd }.ifEmpty { readings }
        return visible.minByOrNull { abs(barCentre(it, style) - t) }
    }

    Canvas(
        modifier = modifier
            .semantics { contentDescription = description }
            .pointerInput(readings, domainStart, domainEnd) {
                detectTapGestures(
                    onDoubleTap = { onViewportChange(ChartViewport()) },
                    onTap = { offset ->
                        if (scrubX != null) {
                            scrubX = null
                            onScrub(null)
                        } else if (offset.x <= plotWidthPx) {
                            scrubX = offset.x
                            onScrub(readingAt(offset.x))
                        }
                    },
                )
            }
            .pointerInput(readings, domainStart, domainEnd) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var scrubbing = false
                    var zooming = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            zooming = true
                            if (scrubbing) {
                                scrubbing = false
                                scrubX = null
                                onScrub(null)
                            }
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid()
                            if (plotWidthPx > 0f) {
                                onViewportChange(
                                    currentViewport.transformed(
                                        factor = zoom,
                                        anchor = (centroid.x / plotWidthPx).coerceIn(0f, 1f),
                                        panFraction = pan.x / plotWidthPx,
                                        minWidth = minWidth,
                                    ),
                                )
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (zooming) continue
                        val change = pressed.first()
                        if (!scrubbing) {
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                                scrubbing = true
                            } else if (abs(dy) > touchSlop) {
                                // A vertical drag belongs to the page's scroll, not the chart.
                                break
                            }
                        }
                        if (scrubbing) {
                            if (change.positionChange() != Offset.Zero) change.consume()
                            val x = change.position.x.coerceIn(0f, plotWidthPx)
                            scrubX = x
                            onScrub(readingAt(x))
                        }
                    }
                }
            },
    ) {
        val axisLabelStyle = HelionType.axisLabel.copy(color = colors.textTertiary)
        val axisHeight = 20.dp.toPx()
        val yLabelWidth = 40.dp.toPx()
        val plotWidth = (size.width - yLabelWidth).coerceAtLeast(1f)
        plotWidthPx = plotWidth
        val topPad = 8.dp.toPx()
        val plotHeight = (size.height - axisHeight - topPad).coerceAtLeast(1f)

        val visible = readings.filter { barCentre(it, style) in visibleStart.toDouble()..visibleEnd.toDouble() }
        val valuesForRange = visible.map { it.value }.ifEmpty { readings.map { it.value } } +
            listOfNotNull(reference?.value)
        if (valuesForRange.isEmpty()) return@Canvas
        val (minY, maxY) = chartYRange(
            valuesForRange.min().toFloat(),
            valuesForRange.max().toFloat(),
            zeroBased = zeroBased || style == ChartStyle.DAILY_BARS,
        )
        val ySpan = (maxY - minY).takeIf { it > 0f } ?: 1f
        val tSpan = (visibleEnd - visibleStart).coerceAtLeast(1L).toFloat()
        fun xOf(t: Double): Float = ((t - visibleStart) / tSpan * plotWidth).toFloat()
        fun yOf(v: Double): Float = topPad + plotHeight - ((v.toFloat() - minY) / ySpan) * plotHeight

        // Value gridlines and their labels on the right edge.
        val grid = chartGridlines(minY, maxY)
        grid.forEach { value ->
            val y = yOf(value.toDouble())
            if (y < topPad - 1f || y > topPad + plotHeight + 1f) return@forEach
            drawLine(
                colors.hairline,
                Offset(0f, y),
                Offset(plotWidth, y),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
            )
            val label = textMeasurer.measure(formatValue(value.toDouble()), axisLabelStyle)
            drawText(label, topLeft = Offset(plotWidth + 8.dp.toPx(), y - label.size.height / 2f))
        }
        // The unit, once, above the value labels.
        if (unit.isNotEmpty()) {
            val unitLabel = textMeasurer.measure(unit, axisLabelStyle)
            drawText(unitLabel, topLeft = Offset(plotWidth + 8.dp.toPx(), 0f))
        }
        // Baseline along the bottom of the plot.
        drawLine(colors.divider, Offset(0f, topPad + plotHeight), Offset(plotWidth, topPad + plotHeight), strokeWidth = 1f)

        // Time ticks.
        val spanSeconds = visibleEnd - visibleStart
        var lastRight = Float.NEGATIVE_INFINITY
        chartTimeMarkers(visibleStart, visibleEnd).forEach { timestamp ->
            val x = xOf(timestamp.toDouble())
            if (x < 0f || x > plotWidth) return@forEach
            drawLine(colors.divider, Offset(x, topPad + plotHeight), Offset(x, topPad + plotHeight + 4.dp.toPx()), strokeWidth = 1f)
            val label = textMeasurer.measure(formatAxisTime(timestamp, spanSeconds), axisLabelStyle)
            // Each label sits centred under its tick. One that would spill past an edge is left
            // out rather than shoved inwards, where it would crowd out its neighbour.
            val left = x - label.size.width / 2f
            if (left >= 0f && left + label.size.width <= plotWidth && left >= lastRight + 6.dp.toPx()) {
                drawText(label, topLeft = Offset(left, size.height - label.size.height))
                lastRight = left + label.size.width
            }
        }

        clipRect(left = 0f, top = 0f, right = plotWidth, bottom = topPad + plotHeight) {
            // Usual range band.
            usualRange?.let { band ->
                val top = yOf(band.endInclusive).coerceIn(topPad, topPad + plotHeight)
                val bottom = yOf(band.start).coerceIn(topPad, topPad + plotHeight)
                if (bottom > top) {
                    drawRect(color.copy(alpha = 0.09f), Offset(0f, top), Size(plotWidth, bottom - top))
                }
            }
            // Reference line.
            reference?.let { ref ->
                val y = yOf(ref.value)
                drawLine(
                    colors.textSecondary.copy(alpha = 0.8f),
                    Offset(0f, y),
                    Offset(plotWidth, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                )
                val label = textMeasurer.measure(ref.label, HelionType.axisLabel.copy(color = colors.textSecondary))
                drawRoundRect(
                    colors.surface,
                    Offset(4.dp.toPx(), y - label.size.height - 3.dp.toPx()),
                    Size(label.size.width + 8.dp.toPx(), label.size.height + 2.dp.toPx()),
                    CornerRadius(3.dp.toPx()),
                )
                drawText(label, topLeft = Offset(8.dp.toPx(), y - label.size.height - 2.dp.toPx()))
            }

            overlays.forEach { overlay ->
                if (overlay.readings.size < 2) return@forEach
                val oMin = overlay.readings.minOf { it.value }.toFloat()
                val oMax = overlay.readings.maxOf { it.value }.toFloat()
                val (lo, hi) = chartYRange(oMin, oMax, zeroBased = false)
                val oSpan = (hi - lo).takeIf { it > 0f } ?: 1f
                val path = Path()
                overlay.readings.forEachIndexed { i, r ->
                    val px = xOf(r.timestamp.toDouble())
                    val py = topPad + plotHeight - ((r.value.toFloat() - lo) / oSpan) * plotHeight
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(
                    path,
                    overlay.color,
                    style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(overlay.dash)),
                )
            }

            clipRect(right = plotWidth * reveal.value) {
                when (style) {
                    // Line only: an area fill turns into a muddy wash on the OLED ground.
                    ChartStyle.LINE -> drawLineSeries(readings, gap, color, ::xOf, ::yOf)
                    ChartStyle.DAILY_BARS -> {
                        val dayWidth = 86_400f / tSpan * plotWidth
                        val barWidth = (dayWidth * 0.62f).coerceIn(2.dp.toPx(), 28.dp.toPx())
                        val selected = scrubX?.let { readingAt(it) }
                        readings.forEach { r ->
                            val cx = xOf(barCentre(r, style))
                            val top = yOf(r.value)
                            val isSelected = selected == r
                            drawRoundRect(
                                color = if (selected == null || isSelected) color else color.copy(alpha = 0.35f),
                                topLeft = Offset(cx - barWidth / 2f, top),
                                size = Size(barWidth, (topPad + plotHeight - top).coerceAtLeast(1f)),
                                cornerRadius = CornerRadius(minOf(barWidth / 2f, 3.dp.toPx())),
                            )
                        }
                    }
                }
            }
        }

        // Scrub cursor and readout -- or a cursor placed from outside (a linked chart).
        val x = scrubX ?: externalCursor?.let { t -> xOf(t.toDouble()).takeIf { it in 0f..plotWidth } }
        val reading = x?.let { readingAt(it) }
        if (reading != null) {
            val px = xOf(barCentre(reading, style)).coerceIn(0f, plotWidth)
            val py = yOf(reading.value)
            drawLine(colors.textSecondary, Offset(px, topPad), Offset(px, topPad + plotHeight), strokeWidth = 1.dp.toPx())
            if (style == ChartStyle.LINE) {
                drawCircle(colors.surface, radius = 6.dp.toPx(), center = Offset(px, py))
                drawCircle(color, radius = 4.dp.toPx(), center = Offset(px, py))
            }
            if (showReadout) drawReadout(textMeasurer, "${formatValue(reading.value)} $unit".trim(), formatTime(reading.timestamp), px, plotWidth, colors.surfaceRaised, colors.divider, colors.textPrimary, colors.textSecondary)
        }
    }
}

/** Where a reading sits on the time axis: a daily total in the middle of its day. */
private fun barCentre(reading: Reading, style: ChartStyle): Double =
    if (style == ChartStyle.DAILY_BARS) reading.timestamp + 43_200.0 else reading.timestamp.toDouble()

private fun DrawScope.drawLineSeries(
    readings: List<Reading>,
    gap: Long,
    color: Color,
    xOf: (Double) -> Float,
    yOf: (Double) -> Float,
) {
    if (readings.isEmpty()) return
    if (readings.size == 1) {
        val r = readings.single()
        drawCircle(color, radius = 3.dp.toPx(), center = Offset(xOf(r.timestamp.toDouble()), yOf(r.value)))
        return
    }
    val stroke = Stroke(width = CHART_LINE_WIDTH.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    var segment = mutableListOf<Reading>()
    fun flush() {
        if (segment.size == 1) {
            val r = segment.single()
            drawCircle(color, radius = 1.6.dp.toPx(), center = Offset(xOf(r.timestamp.toDouble()), yOf(r.value)))
        } else if (segment.size > 1) {
            val line = Path()
            segment.forEachIndexed { i, r ->
                val px = xOf(r.timestamp.toDouble())
                val py = yOf(r.value)
                if (i == 0) line.moveTo(px, py) else line.lineTo(px, py)
            }
            drawPath(line, color, style = stroke)
        }
        segment = mutableListOf()
    }
    readings.forEach { r ->
        val previous = segment.lastOrNull()
        if (previous != null && r.timestamp - previous.timestamp > gap) flush()
        segment.add(r)
    }
    flush()
}

/** The readout chip that follows the cursor, kept inside the plot at both edges. */
private fun DrawScope.drawReadout(
    measurer: TextMeasurer,
    value: String,
    time: String,
    x: Float,
    plotWidth: Float,
    background: Color,
    border: Color,
    valueColor: Color,
    timeColor: Color,
) {
    val valueLayout = measurer.measure(value, HelionType.valueSmall.copy(color = valueColor))
    val timeLayout = measurer.measure(time, HelionType.axisLabel.copy(color = timeColor))
    val padH = 8.dp.toPx()
    val padV = 5.dp.toPx()
    val w = maxOf(valueLayout.size.width, timeLayout.size.width) + padH * 2
    val h = valueLayout.size.height + timeLayout.size.height + padV * 2
    val left = (x - w / 2f).coerceIn(0f, (plotWidth - w).coerceAtLeast(0f))
    val top = 0f
    drawRoundRect(background, Offset(left, top), Size(w, h), CornerRadius(6.dp.toPx()))
    drawRoundRect(border, Offset(left, top), Size(w, h), CornerRadius(6.dp.toPx()), style = Stroke(1f))
    drawText(valueLayout, topLeft = Offset(left + padH, top + padV))
    drawText(timeLayout, topLeft = Offset(left + padH, top + padV + valueLayout.size.height))
}
