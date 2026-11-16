package ch.kevinjordil.helion.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ch.kevinjordil.helion.R

/**
 * The app's single face, for every word and every figure: IBM Plex Sans, a neo-grotesque
 * in the same family of technical sans faces as Inter, with tall digits that stay distinct
 * at small sizes (a slashed-free 0, an open 4, a 1 with a foot).
 *
 * Bundled as static .ttf resources rather than through the downloadable-fonts API: no
 * network fetch, no first-run flash of a fallback face, no new dependency.
 */
val PlexSans = FontFamily(
    Font(R.font.ibmplexsans_regular, FontWeight.Normal),
    Font(R.font.ibmplexsans_medium, FontWeight.Medium),
    Font(R.font.ibmplexsans_semibold, FontWeight.SemiBold),
)

/**
 * Tabular figures, asked for explicitly on every style. Plex's default digits already share
 * one advance, but stating it keeps a value that ticks (a scrub readout, a count-up, a
 * column of stats) from shifting sideways if the face ever changes.
 */
private const val TABULAR = "tnum"

/** Trims the font's built-in top and bottom padding so text sits where its box says. */
private val TIGHT = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Float = 0f) = TextStyle(
    fontFamily = PlexSans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
    fontFeatureSettings = TABULAR,
    lineHeightStyle = TIGHT,
)

/**
 * Helion's type scale. Three registers, each recognisable without reading the words:
 *
 * - **Figures** ([hero], [valueLarge], [valueMedium], [valueSmall]): semibold, slightly
 *   tightened, always the strongest thing in their card.
 * - **Labels** ([label], [labelSmall], [overline], [axisLabel]): medium weight, compact,
 *   in a secondary colour. A unit is a label set beside its figure, never the figure's size.
 * - **Prose** ([headline], [title], [body], [bodySmall]): sentences and headings.
 */
object HelionType {

    /** The one dominant figure on a screen: a metric's current value, a night's duration. */
    val hero: TextStyle = style(48, 52, FontWeight.SemiBold, tracking = -0.03f)

    /** A dashboard tile's value. */
    val valueLarge: TextStyle = style(28, 32, FontWeight.SemiBold, tracking = -0.02f)

    /** A figure in a stats row, beside its siblings. */
    val valueMedium: TextStyle = style(20, 24, FontWeight.SemiBold, tracking = -0.01f)

    /** A figure inside a list row or a legend. */
    val valueSmall: TextStyle = style(15, 20, FontWeight.SemiBold)

    /** A numeral drawn directly on a chart's canvas: an axis tick, a gridline value. */
    val axisLabel: TextStyle = style(10, 13, FontWeight.Medium, tracking = 0.01f)

    /** A screen's own title, at the top of the page. */
    val headline: TextStyle = style(24, 30, FontWeight.SemiBold, tracking = -0.015f)

    /** A card's or a section's title. */
    val title: TextStyle = style(16, 22, FontWeight.SemiBold, tracking = -0.005f)

    /** A short word label: a metric's name, a selector option, a button. */
    val label: TextStyle = style(13, 18, FontWeight.Medium)

    /** A smaller label: a unit, a stat's caption, a status. */
    val labelSmall: TextStyle = style(12, 16, FontWeight.Medium)

    /** A section heading above a group of cards, set in capitals by the caller. */
    val overline: TextStyle = style(11, 14, FontWeight.SemiBold, tracking = 0.08f)

    /** Prose: notes, descriptions, empty-state copy. */
    val body: TextStyle = style(15, 21, FontWeight.Normal)

    /** Smaller prose: captions, secondary lines. */
    val bodySmall: TextStyle = style(13, 18, FontWeight.Normal)
}
