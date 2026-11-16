package ch.kevinjordil.helion.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.ui.theme.HelionMotion
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.rememberReducedMotion

/**
 * A tile's 24-hour line: one segment per run of consecutive measured buckets, a small dot
 * on the latest bucket so the current value has a place on the line, and a faint baseline
 * for scale. Revealed left to right once, when the tile first appears.
 */
@Composable
fun TileSparkline(points: List<TilePoint?>, color: Color, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    val reveal = rememberReveal()
    Canvas(modifier = modifier) {
        val inset = 3.dp.toPx()
        val h = size.height - 2 * inset
        fun at(p: TilePoint) = Offset(p.x * size.width, inset + (1f - p.y) * h)

        drawLine(colors.hairline, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), strokeWidth = 1f)

        val clipRight = size.width * reveal
        var path: Path? = null
        var last: TilePoint? = null
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        points.forEach { point ->
            if (point == null || point.x * size.width > clipRight) {
                path?.let { drawPath(it, color, style = stroke) }
                path = null
            } else {
                val o = at(point)
                val current = path
                if (current == null) {
                    path = Path().apply { moveTo(o.x, o.y) }
                } else {
                    current.lineTo(o.x, o.y)
                }
                last = point
            }
        }
        path?.let { drawPath(it, color, style = stroke) }
        // A lone bucket between gaps draws as a dot rather than vanishing.
        points.forEachIndexed { i, p ->
            if (p != null && points.getOrNull(i - 1) == null && points.getOrNull(i + 1) == null && p.x * size.width <= clipRight) {
                drawCircle(color, radius = 1.6.dp.toPx(), center = at(p))
            }
        }
        last?.takeIf { reveal >= 1f }?.let { p ->
            drawCircle(colors.surface, radius = 3.6.dp.toPx(), center = at(p))
            drawCircle(color, radius = 2.4.dp.toPx(), center = at(p))
        }
    }
}

/**
 * A tile's bars: one per bucket, rising from the bottom, in the metric's colour. Missing
 * buckets keep a faint stub so the extent of the window stays readable.
 */
@Composable
fun TileBars(points: List<TilePoint?>, color: Color, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    val reveal = rememberReveal()
    Canvas(modifier = modifier) {
        if (points.isEmpty()) return@Canvas
        val slot = size.width / points.size
        val barWidth = (slot * 0.58f).coerceIn(2.dp.toPx(), 9.dp.toPx())
        val radius = CornerRadius(minOf(barWidth / 2f, 2.dp.toPx()))
        val minHeight = 2.dp.toPx()
        // Empty slots get a faint stub so the window's extent stays readable.
        points.forEachIndexed { index, point ->
            val cx = (index + 0.5f) * slot
            if (point == null) {
                drawRoundRect(colors.hairline, Offset(cx - barWidth / 2f, size.height - minHeight), Size(barWidth, minHeight), radius)
            } else {
                val barHeight = (minHeight + point.y * (size.height - minHeight)) * reveal
                drawRoundRect(color, Offset(cx - barWidth / 2f, size.height - barHeight), Size(barWidth, barHeight), radius)
            }
        }
    }
}

/** A goal's progress: a dim track and a fill to the reached fraction, capped at the track. */
@Composable
fun GoalTrack(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    val reveal = rememberReveal()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(colors.surfaceRaised),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth((fraction.coerceIn(0f, 1f) * reveal).coerceAtLeast(0.001f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
    }
}

/** 0 to 1 once, when the chart first appears; immediately 1 when animations are off. */
@Composable
private fun rememberReveal(): Float {
    val reduced = rememberReducedMotion()
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) progress.animateTo(1f, tween(HelionMotion.LONG, delayMillis = 120, easing = HelionMotion.Emphasized))
    }
    return progress.value
}
