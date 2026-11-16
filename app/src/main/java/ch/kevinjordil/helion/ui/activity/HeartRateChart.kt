package ch.kevinjordil.helion.ui.activity

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.store.MinuteSample

/** The smallest heart-rate range the chart's height stands for, in bpm. */
private const val MIN_SPAN_BPM = 30

/** One heart-rate reading placed on the chart: x and y both in 0..1, y = 0 at the top. */
data class ChartPoint(val x: Float, val y: Float)

/**
 * The polyline [HeartRateChart] draws, as fractions of the chart box, so the geometry is
 * testable without drawing anything. Minutes without a reading are skipped, and a gap of more
 * than [maxGapSeconds] between two readings starts a new segment rather than drawing a
 * straight line across time nothing was measured. Empty when there are fewer than two
 * readings -- one dot says nothing about the shape of a session.
 */
fun heartRateChartSegments(
    samples: List<MinuteSample>,
    start: Long,
    end: Long,
    maxGapSeconds: Long = 5 * 60,
): List<List<ChartPoint>> {
    val readings = samples.mapNotNull { sample -> sample.heartRate?.let { sample.timestamp to it } }
    if (readings.size < 2 || end <= start) return emptyList()
    val low = readings.minOf { it.second }
    val high = readings.maxOf { it.second }
    // At least MIN_SPAN_BPM tall, centred on the readings: scaling a calm 61-64 bpm session
    // to the full height would draw it as dramatic as an interval workout. Also keeps a
    // perfectly flat session (every reading equal) on the middle line, never a division by zero.
    val span = maxOf(high - low, MIN_SPAN_BPM).toFloat()
    val base = low - (span - (high - low)) / 2f

    val segments = mutableListOf<MutableList<ChartPoint>>()
    var previous: Long? = null
    for ((timestamp, bpm) in readings) {
        if (previous == null || timestamp - previous > maxGapSeconds) segments += mutableListOf<ChartPoint>()
        val x = ((timestamp - start).toFloat() / (end - start)).coerceIn(0f, 1f)
        val y = 1f - (bpm - base) / span
        segments.last() += ChartPoint(x, y)
        previous = timestamp
    }
    return segments.filter { it.size >= 2 }
}

/**
 * The session's heart rate over time -- the one thing that tells at a glance whether a
 * detected candidate really was a training session, which is exactly what the owner decides
 * on this screen. Scaled to the session's own readings (with a minimum range, see
 * [heartRateChartSegments]), since the shape is what matters here; the figures themselves
 * are in the stats row above it.
 */
@Composable
fun HeartRateChart(
    samples: List<MinuteSample>,
    start: Long,
    end: Long,
    color: Color,
    description: String,
    modifier: Modifier = Modifier,
) {
    val segments = heartRateChartSegments(samples, start, end)
    if (segments.isEmpty()) return
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .semantics { contentDescription = description },
    ) {
        val strokeWidth = 2.dp.toPx()
        // Keeps the line's own thickness inside the box at its highest and lowest points.
        val inset = strokeWidth
        val drawHeight = size.height - 2 * inset
        segments.forEach { segment ->
            val path = Path()
            segment.forEachIndexed { index, point ->
                val offset = Offset(point.x * size.width, inset + point.y * drawHeight)
                if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
            }
            drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
