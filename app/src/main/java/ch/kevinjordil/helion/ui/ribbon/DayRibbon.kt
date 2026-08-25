package ch.kevinjordil.helion.ui.ribbon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

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

/**
 * One bar of a ribbon whose colour itself is the value, rather than [RibbonBar]'s height --
 * for a categorical track (e.g. an estimated sleep phase, see
 * [ch.kevinjordil.helion.ui.sleep.SleepPhase]) where there is no "how much", only "which".
 */
data class ColorBar(val xFraction: Float, val color: Color)

/**
 * Same drawing as [DayRibbon], full height throughout since [ColorBar] carries no
 * magnitude -- see its kdoc. Kept in this file rather than a separate chart so a
 * categorical track still visibly shares the ribbon's spine.
 */
@Composable
fun PhaseRibbon(bars: List<ColorBar>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (bars.isEmpty()) return@Canvas

        val strokeWidth = (size.width / 96f).coerceIn(1.5f, 4f)
        bars.forEach { bar ->
            val x = bar.xFraction * size.width
            drawLine(
                color = bar.color,
                start = Offset(x, size.height),
                end = Offset(x, 0f),
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
