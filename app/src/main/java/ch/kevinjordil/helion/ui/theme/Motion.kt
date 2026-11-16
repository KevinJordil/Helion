package ch.kevinjordil.helion.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle

/**
 * Helion's motion: short, eased, and never looping. Things move to explain a change (a
 * screen pushed on top of another, a range switched, a value updated), never to decorate.
 */
object HelionMotion {
    /** Standard easing: quick start, long settle. */
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** A state change inside a component: a segment sliding, a colour changing. */
    const val SHORT = 160

    /** A screen transition, a chart redrawing for a new range. */
    const val MEDIUM = 280

    /** A figure counting to its value. */
    const val LONG = 650
}

/**
 * Whether the owner has turned animations off ("Supprimer les animations" sets the animator
 * duration scale to 0). Compose already shortens its own animations to nothing in that case;
 * this is for the few places that decide whether to animate at all.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/**
 * A figure that eases to a new value instead of jumping, formatted through [format] at
 * every frame, so a refreshed reading visibly moves from the old number to the new one.
 * The first value appears as it is -- counting up from zero would show numbers that were
 * never measured.
 */
@Composable
fun AnimatedFigure(
    value: Double,
    format: (Double) -> String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val reduced = rememberReducedMotion()
    val animatable = remember { Animatable(value.toFloat()) }
    LaunchedEffect(value) {
        if (reduced) {
            animatable.snapTo(value.toFloat())
        } else {
            animatable.animateTo(value.toFloat(), tween(HelionMotion.LONG, easing = HelionMotion.Emphasized))
        }
    }
    // Once settled, the exact value is formatted rather than the float the animation landed
    // on, so rounding never differs from the same value shown elsewhere.
    val shown = if (animatable.isRunning) animatable.value.toDouble() else value
    Text(format(shown), style = style, color = color, modifier = modifier)
}
