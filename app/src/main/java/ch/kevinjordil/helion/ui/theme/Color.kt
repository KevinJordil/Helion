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
 * by Sommeil's hypnogram lanes and history bars: distinguishing sleep phases legibly needs
 * more than one hue, which [accentViolet]/[accentAmber] alone cannot give without either
 * borrowing "live data" violet for something it does not mean or pressing "needs your
 * attention" amber into decorative service (see [accentAmber]'s kdoc -- it must not become
 * that).
 *
 * [phaseAwake], [phaseLight] and [phaseDeep] are three points on one blue ramp, because sleep
 * *depth* is ordinal, not categorical -- awake, light and deep sit on a single axis, so one
 * hue stepped by lightness reads as "how deep" without a legend, the way a heatmap reads
 * without one. Each theme steps that ramp towards its own ground: on [HelionDarkColors]'
 * dark ground, [phaseAwake] sits close to [divider] (dark, low-chroma, the quietest of the
 * three -- being briefly awake is the least informative state on the chart) and [phaseDeep]
 * is the lightest, most saturated step, the one that visibly pops off a dark background. On
 * [HelionLightColors]' light ground the same ordinal logic runs the other way -- [phaseAwake]
 * is the lightest, near-white step and [phaseDeep] the darkest, most saturated one -- because
 * "recessive" and "prominent" are about contrast against *that* theme's own ground, not a
 * fixed hex value; see [HelionLightColors]' own kdoc on why every pairing is tuned per theme
 * rather than a tint of the dark one. [phaseRem] is not on this ramp at all -- REM is not
 * "deeper" or "lighter" than the other three, just different -- so it keeps its own separate,
 * harmonious cool hue (teal), far enough from the blue ramp in hue to stay distinct at a
 * glance and from [accentAmber] and [MetricPalette.magenta] (the metric hue most likely to
 * share a screen with it, as Sommeil's respiratory-rate overlay) to avoid either kind of
 * mix-up.
 *
 * All four are chosen with distinct lightness, not just distinct hue, which matters twice
 * over here: it is what makes the ramp itself read as "depth" at all, and it is also what
 * keeps the phase legend legible for colour-vision deficiency or in a grayscale screenshot,
 * where hue disappears but lightness does not -- tritanopia in particular flattens exactly
 * the blue/yellow distinction this ramp leans on, so the lightness step between each pair is
 * deliberately large enough to still separate them once hue is unreliable. The lane's own
 * vertical position (see [ch.kevinjordil.helion.ui.sleep.NightChartSection]'s hypnogram) is
 * the secondary channel that means this subtle a palette does not have to carry the reading
 * alone; the accompanying label is what actually carries the name either way, per
 * [ch.kevinjordil.helion.ui.sleep.SleepScreen]'s legend.
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
    val metricHues: List<Color>,
)

/**
 * Eight hues, evenly spread around the wheel and ordered so no two neighbours resemble
 * each other, validated against a colour-blindness simulator for both deuteranopia and
 * tritanopia and checked for contrast against both [HelionColors.surface] values. One
 * warning survives that validation: [teal] sits at only 2.85:1 against the light surface,
 * below the usual text-contrast bar -- acceptable here only because a metric is never
 * identified by hue alone, always alongside its (fully-contrasted) text label. Whichever
 * hue a metric is given, see [ch.kevinjordil.helion.ui.metric.metricColor] for how that
 * assignment is made and why it never depends on list order.
 *
 * These are mid-tones, used as-is in both [HelionDarkColors] and [HelionLightColors]: a
 * theme that needs a lighter or darker step for a fill or a wash (e.g. the soft area under
 * a detail chart's curve) derives it from one of these eight via alpha or a lightness
 * shift, rather than inventing a ninth colour.
 *
 * [orange] sits close to [HelionColors.accentAmber], the app's one existing semantic
 * colour ("this needs your attention"). Rather than let a metric's identity get read as an
 * attention state, [orange] is deliberately assigned to skin temperature -- see
 * [ch.kevinjordil.helion.ui.metric.metricColor]'s kdoc -- the one metric with no
 * reference-axis comparison and the steadiest personal baseline, so it is the metric least
 * likely to ever actually appear amber on its own screen.
 */
object MetricPalette {
    val red = Color(0xFFC74A51)
    val teal = Color(0xFF00A7B2)
    val olive = Color(0xFF8D8D00)
    val violet = Color(0xFF735CC7)
    val orange = Color(0xFFD37900)
    val blue = Color(0xFF0080D1)
    val green = Color(0xFF008B45)
    val magenta = Color(0xFFC562B2)
}

/**
 * [MetricPalette]'s eight hues in the fixed order [ch.kevinjordil.helion.ui.metric.metricColor]
 * indexes into -- heart rate, HRV, stress, SpO2, PAI, steps, skin temperature, respiratory
 * rate. Kept as one list here, identical in both themes, so there is exactly one place to
 * change any of these eight values.
 */
private val HELION_METRIC_HUES: List<Color> = listOf(
    MetricPalette.red,
    MetricPalette.teal,
    MetricPalette.olive,
    MetricPalette.violet,
    MetricPalette.green,
    MetricPalette.blue,
    MetricPalette.orange,
    MetricPalette.magenta,
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
    phaseAwake = Color(0xFF333A4C),
    phaseLight = Color(0xFF4E72C9),
    phaseRem = Color(0xFF2FBFAE),
    phaseDeep = Color(0xFF9DBBFF),
    metricHues = HELION_METRIC_HUES,
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
    phaseAwake = Color(0xFFCDD4E4),
    phaseLight = Color(0xFF5B7FDA),
    phaseRem = Color(0xFF1C9C89),
    phaseDeep = Color(0xFF2C4A9E),
    metricHues = HELION_METRIC_HUES,
)
