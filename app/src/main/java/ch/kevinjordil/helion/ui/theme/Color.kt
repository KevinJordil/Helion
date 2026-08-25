package ch.kevinjordil.helion.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Helion's full palette: a dark graphite-blue ground, two or three neutral surface steps,
 * three text weights, and exactly two accents, each with one meaning.
 *
 * - [accentViolet] is data: the live thing, the current value. It is the only colour a
 *   number is ever drawn in.
 * - [accentAmber] means "this needs your attention" -- a stale series, a failed refresh,
 *   an empty state. It is never decorative; if amber is on screen, something is off.
 *
 * No third accent. Everything else in the UI is drawn from these neutrals.
 *
 * [phaseAwake], [phaseLight], [phaseRem] and [phaseDeep] are a scoped exception, used only
 * by Sommeil's phase ribbon and legend: distinguishing four sleep phases legibly needs more
 * than one hue, which [accentViolet]/[accentAmber] alone cannot give without either
 * borrowing "live data" violet for something it does not mean or pressing "needs your
 * attention" amber into decorative service (see [accentAmber]'s kdoc -- it must not become
 * that). [phaseLight] and [phaseDeep] are two points on one violet ramp -- lighter/quieter
 * for light sleep, more saturated/prominent for deep -- so the depth-of-sleep phases still
 * read as one family related to (but visibly distinct from) [accentViolet]. [phaseRem] is a
 * separate, harmonious cool hue (teal) rather than a third violet step, since REM is not
 * "deeper" or "lighter" than the others, just different. [phaseAwake] is deliberately the
 * quietest of the four, close to [divider], because being awake briefly is the least
 * informative state on the chart. All four are chosen with distinct lightness, not just
 * distinct hue, so the phase legend still reads correctly for colour-vision deficiency or
 * in a grayscale screenshot -- the accompanying label is still what actually carries the
 * name, per [ch.kevinjordil.helion.ui.sleep.SleepScreen]'s legend.
 */
data class HelionColors(
    val ground: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val divider: Color,
    val accentViolet: Color,
    val onAccentViolet: Color,
    val accentAmber: Color,
    val onAccentAmber: Color,
    val phaseAwake: Color,
    val phaseLight: Color,
    val phaseRem: Color,
    val phaseDeep: Color,
)

/**
 * Dark graphite-blue ground. The default theme: he opens this at 7am in bed as often as
 * he opens it in daylight, so both palettes are tuned for that, not just this one.
 */
val HelionDarkColors = HelionColors(
    ground = Color(0xFF10141C),
    surface = Color(0xFF171C26),
    surfaceRaised = Color(0xFF1F2530),
    textPrimary = Color(0xFFF2F4F8),
    textSecondary = Color(0xFFA9B1C0),
    textTertiary = Color(0xFF6B7385),
    divider = Color(0xFF2A3140),
    accentViolet = Color(0xFF8B6CFF),
    onAccentViolet = Color(0xFF10141C),
    accentAmber = Color(0xFFE8A23D),
    onAccentAmber = Color(0xFF10141C),
    phaseAwake = Color(0xFF3D4454),
    phaseLight = Color(0xFF6A5B99),
    phaseRem = Color(0xFF2FBFAE),
    phaseDeep = Color(0xFFB49CFF),
)

/**
 * A genuine inversion, not a tint of the dark palette: separately tuned lightnesses so
 * every pairing still clears comfortable contrast on its own ground.
 */
val HelionLightColors = HelionColors(
    ground = Color(0xFFF4F5F9),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFE9EBF2),
    textPrimary = Color(0xFF12151D),
    textSecondary = Color(0xFF4B5163),
    textTertiary = Color(0xFF7B8296),
    divider = Color(0xFFD8DBE4),
    accentViolet = Color(0xFF6C3FE0),
    onAccentViolet = Color(0xFFFFFFFF),
    accentAmber = Color(0xFFB0650C),
    onAccentAmber = Color(0xFFFFFFFF),
    phaseAwake = Color(0xFFC7CBD8),
    phaseLight = Color(0xFFAF9BE0),
    phaseRem = Color(0xFF1C9C89),
    phaseDeep = Color(0xFF5A2FC9),
)
