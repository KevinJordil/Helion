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
)
