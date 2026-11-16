package ch.kevinjordil.helion.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Helion's palette: one OLED-black theme, no light variant.
 *
 * Neutrals step up from the ground in a single cool graphite hue:
 * - [ground] is the screen itself, near-black so an OLED panel switches its pixels off.
 * - [surfaceDim] is a secondary surface sitting directly on the ground: a segmented
 *   control's track, a chart's well, an icon's tile.
 * - [surface] is a card.
 * - [surfaceRaised] sits above a card: a selected segment, a scrub readout, a pressed row.
 *
 * Lines come in two weights. [divider] is a border that has to be seen (a field, an
 * outlined button). [hairline] is the quiet edge of a card or a separator inside one.
 *
 * Three text weights, then the colours that carry meaning:
 * - [accent] (electric blue) is the identity: the active tab, the selected range, the
 *   primary action, a link. It is the only colour an interactive element is ever drawn in.
 * - [attention] means "this needs a look": a stale series, a failed sync, a notable
 *   departure from the usual. It is never decorative.
 * - [positive] confirms a working connection or a completed action, and nothing else.
 *
 * [metricHues] identify data rather than decorate it. A metric's hue is used for its
 * icon and its chart, never for a card's background, so a screen full of metrics stays a
 * black instrument panel with eight coloured traces on it. Figures themselves are drawn in
 * [textPrimary]: the number is the most important thing on a card and reads best in white.
 *
 * The four sleep-phase colours are a scoped set for Sommeil's hypnogram, legend and phase
 * bar. Light and deep sleep are two steps of one blue ramp because depth is ordinal (deeper
 * is darker and more saturated); REM is a separate violet because it is a different kind of
 * sleep, not a depth; awake is the one warm colour, since time awake interrupts the night.
 * Each pair also differs in lightness, so the phases stay apart in greyscale or with
 * colour-vision deficiency, and every phase is always shown with its name.
 */
data class HelionColors(
    val ground: Color,
    val surfaceDim: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val divider: Color,
    val hairline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val attention: Color,
    val onAttention: Color,
    val positive: Color,
    val phaseAwake: Color,
    val phaseLight: Color,
    val phaseRem: Color,
    val phaseDeep: Color,
    val metricHues: List<Color>,
)

/**
 * The eight data hues, tuned for a black ground: mid-to-high lightness so a 2dp line reads
 * clearly, moderate chroma so nothing glows. Ordered so no two that commonly share a screen
 * are neighbours on the wheel. Yellow is deliberately absent: it is [HelionColors.attention]'s.
 */
object MetricPalette {
    val red = Color(0xFFFF5C6C)
    val turquoise = Color(0xFF34D5C1)
    val orange = Color(0xFFFF9447)
    val pink = Color(0xFFEC7BD2)
    val lime = Color(0xFFA6DB4F)
    val blue = Color(0xFF4C93FF)
    val violet = Color(0xFFA48BFF)
    val sky = Color(0xFF68C6FF)
}

/**
 * [MetricPalette]'s hues in the fixed order [ch.kevinjordil.helion.ui.metric.metricColor]
 * indexes into: heart rate, HRV, stress, SpO2, PAI, steps, skin temperature, respiratory rate.
 */
private val HELION_METRIC_HUES: List<Color> = listOf(
    MetricPalette.red,
    MetricPalette.turquoise,
    MetricPalette.orange,
    MetricPalette.pink,
    MetricPalette.lime,
    MetricPalette.blue,
    MetricPalette.violet,
    MetricPalette.sky,
)

val HelionDarkColors = HelionColors(
    ground = Color(0xFF050608),
    surfaceDim = Color(0xFF10151C),
    surface = Color(0xFF171D26),
    surfaceRaised = Color(0xFF222A35),
    divider = Color(0xFF293441),
    hairline = Color(0xFF1F2732),
    textPrimary = Color(0xFFF5F7FA),
    textSecondary = Color(0xFF9AA9BA),
    // Lifted slightly from #718196 so the smallest captions keep 4.5:1 on a card.
    textTertiary = Color(0xFF7A8A9F),
    accent = Color(0xFF438EFF),
    // Near-black on the blue rather than white: 6:1 instead of 3.3:1, so button labels
    // stay legible at their size.
    onAccent = Color(0xFF050608),
    attention = Color(0xFFFFC04D),
    onAttention = Color(0xFF050608),
    positive = Color(0xFF3DD68C),
    phaseAwake = Color(0xFFF39C7B),
    phaseLight = Color(0xFF6FA4FF),
    phaseRem = Color(0xFFA98BFF),
    phaseDeep = Color(0xFF2F55D4),
    metricHues = HELION_METRIC_HUES,
)
