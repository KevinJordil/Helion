package ch.kevinjordil.helion.ui.ribbon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.ui.theme.HelionType

/**
 * Helion's signature element: a 24-hour band built from [RibbonBar]s (see
 * [buildRibbon]). The same composable is used at all three scales the design calls for --
 * full-bleed behind the hero, a small strand on a tile, and the axis of the detail screen
 * -- only [modifier] (size) and [barColor] change, so every metric visibly shares the same
 * spine and the dashboard reads as one day rather than seven unrelated numbers.
 *
 * Draws nothing for an empty [bars] list, which is exactly right: the caller passed no
 * bars because there is no data in the window, and an empty canvas *is* that gap.
 */
@Composable
fun DayRibbon(
    bars: List<RibbonBar>,
    barColor: Color,
    modifier: Modifier = Modifier,
    minHeightFraction: Float = 0.12f,
) {
    Canvas(modifier = modifier) {
        if (bars.isEmpty()) return@Canvas

        val strokeWidth = (size.width / 96f).coerceIn(1.5f, 4f)
        bars.forEach { bar ->
            val x = bar.xFraction * size.width
            val barHeight = (minHeightFraction + bar.valueFraction * (1f - minHeightFraction)) * size.height
            drawLine(
                color = barColor,
                start = Offset(x, size.height),
                end = Offset(x, size.height - barHeight),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Preconfigured height for the small strand shown on a tile. */
fun Modifier.tileRibbonSize(): Modifier = this.fillMaxWidth().height(28.dp)

/** Preconfigured height for the full-bleed ribbon behind the hero. */
fun Modifier.heroRibbonSize(): Modifier = this.fillMaxWidth().height(120.dp)

/**
 * A hypnogram: one horizontal lane per category in [lanes] (top to bottom, in the order
 * given), each lane showing only the buckets of [bars] that belong to it. Unlike a single
 * blended track whose colour alone tells one stage from another, a stage switching lanes
 * is a visible vertical jump -- this is what actually shows *when* the night moved between
 * stages, which a single-lane categorical ribbon or a stacked share breakdown both hide.
 *
 * Each lane carries its own short label so which lane is which never depends on colour
 * alone (legible without colour vision, and in a grayscale capture).
 *
 * [bars] is `xFraction to category`, exactly [buildCategoryRibbon]'s output.
 */
@Composable
fun <T> HypnogramRibbon(
    bars: List<Pair<Float, T>>,
    lanes: List<T>,
    laneColor: (T) -> Color,
    laneLabel: (T) -> String,
    labelColor: Color,
    modifier: Modifier = Modifier,
    laneHeight: Dp = 22.dp,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lanes.forEach { lane ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    laneLabel(lane).uppercase(),
                    style = HelionType.labelSmall,
                    color = labelColor,
                    // 90dp: comfortably wider than the 136dp tile budget TileTextWidthTest
                    // already verifies these same four phase labels fit at a larger 12sp
                    // font with more letter spacing -- labelSmall here is both smaller and
                    // tighter, and this lane label never fights another tile for width.
                    modifier = Modifier.width(90.dp),
                )
                Canvas(modifier = Modifier.fillMaxWidth().height(laneHeight)) {
                    if (bars.isEmpty()) return@Canvas
                    val strokeWidth = (size.width / 96f).coerceIn(1.5f, 4f)
                    bars.forEach { (xFraction, category) ->
                        if (category != lane) return@forEach
                        val x = xFraction * size.width
                        drawLine(
                            color = laneColor(lane),
                            start = Offset(x, size.height),
                            end = Offset(x, 0f),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }
    }
}
