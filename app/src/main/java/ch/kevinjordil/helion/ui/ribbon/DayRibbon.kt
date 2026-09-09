package ch.kevinjordil.helion.ui.ribbon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.ui.metric.chartTimeMarkers
import ch.kevinjordil.helion.ui.metric.formatAxisTimestamp
import ch.kevinjordil.helion.ui.theme.HelionType

/** Height reserved for hour ticks and labels when [DayRibbon] is asked to draw them. */
private val AXIS_HEIGHT = 14.dp

/**
 * Helion's signature element: a 24-hour band built from [RibbonBar]s (see
 * [buildRibbon]). The same composable is used at all three scales the design calls for --
 * full-bleed behind the hero, a small strand on a tile, and the axis of the detail screen
 * -- only [modifier] (size) and [barColor] change, so every metric visibly shares the same
 * spine and the dashboard reads as one day rather than seven unrelated numbers.
 *
 * Draws nothing for an empty [bars] list, which is exactly right: the caller passed no
 * bars because there is no data in the window, and an empty canvas *is* that gap.
 *
 * [windowStart]/[windowEnd] are optional and, when both given, add a row of quiet hour
 * ticks and labels along the bottom (see [chartTimeMarkers]), in [axisLabelColor] -- the
 * bars alone have no time reference at all otherwise, only a shape. Left null (the
 * default), nothing changes from before: a tile's 28dp strand has no room for an axis and
 * never asks for one.
 */
@Composable
fun DayRibbon(
    bars: List<RibbonBar>,
    barColor: Color,
    modifier: Modifier = Modifier,
    minHeightFraction: Float = 0.12f,
    windowStart: Long? = null,
    windowEnd: Long? = null,
    axisLabelColor: Color = barColor,
    topReservedFraction: Float = 0f,
) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        val showAxis = windowStart != null && windowEnd != null && windowEnd > windowStart
        val axisHeightPx = if (showAxis) AXIS_HEIGHT.toPx() else 0f
        // The band the bars may occupy. [topReservedFraction] hands the strip above it to
        // the caller -- the hero draws its figure there, so the number never lands in the
        // bars however tall the day's peak is.
        val plotHeight = (size.height - axisHeightPx).coerceAtLeast(0f)
        val barAreaHeight = plotHeight * (1f - topReservedFraction.coerceIn(0f, 0.9f))
        val barBaseline = plotHeight

        if (bars.isNotEmpty()) {
            val strokeWidth = (size.width / 96f).coerceIn(1.5f, 4f)
            bars.forEach { bar ->
                val x = bar.xFraction * size.width
                val barHeight = (minHeightFraction + bar.valueFraction * (1f - minHeightFraction)) * barAreaHeight
                drawLine(
                    color = barColor,
                    start = Offset(x, barBaseline),
                    end = Offset(x, barBaseline - barHeight),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        }

        if (showAxis) {
            val span = windowEnd!! - windowStart!!
            var lastLabelRight = Float.NEGATIVE_INFINITY
            chartTimeMarkers(windowStart, windowEnd).forEach { timestamp ->
                val x = ((timestamp - windowStart).toFloat() / span) * size.width
                val label = textMeasurer.measure(
                    formatAxisTimestamp(timestamp, span),
                    HelionType.axisLabel.copy(color = axisLabelColor),
                )
                val labelLeft = (x - label.size.width / 2f).coerceIn(0f, (size.width - label.size.width).coerceAtLeast(0f))
                if (labelLeft >= lastLabelRight) {
                    drawText(label, topLeft = Offset(labelLeft, size.height - label.size.height))
                    lastLabelRight = labelLeft + label.size.width
                }
            }
        }
    }
}

/** Preconfigured height for the small strand shown on a tile. */
fun Modifier.tileRibbonSize(): Modifier = this.fillMaxWidth().height(28.dp)

/** Preconfigured height for the full-bleed ribbon behind the hero, including its hour axis. */
fun Modifier.heroRibbonSize(): Modifier = this.fillMaxWidth().height(120.dp + AXIS_HEIGHT)
