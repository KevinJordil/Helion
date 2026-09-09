package ch.kevinjordil.helion.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ch.kevinjordil.helion.R

/**
 * The app's single face: every word and every figure. Figures are set apart from words by
 * weight and size (see [HelionType]) rather than by a second family -- a
 * monospaced face was tried for numerals and retired, because it gave separators a full
 * digit cell and broke composed values apart ("6 h 18", "23:10").
 *
 * Bundled as static .ttf resources rather than the downloadable-fonts API: no network
 * fetch, no first-run flash of a fallback face, and no new Gradle dependency.
 */
val PlexSans = FontFamily(
    Font(R.font.ibmplexsans_regular, FontWeight.Normal),
    Font(R.font.ibmplexsans_medium, FontWeight.Medium),
    Font(R.font.ibmplexsans_semibold, FontWeight.SemiBold),
)

/**
 * Helion's type scale. Kept as a flat set of named styles rather than shoehorned into
 * Material3's full type scale -- this UI does not use most of Material's roles (no cards,
 * no filled buttons), so naming them for what they actually are here is clearer than
 * borrowing "displayLarge" etc. for meanings Material never intended.
 *
 * Three deliberate steps separate a headline figure from a supporting figure from a caption
 * ([hero] to [valueLarge]/[valueMedium] to [label]/[labelSmall]/[bodySmall]), rather than the
 * half-dozen closely-sized styles a console UI tends to accumulate: a reader should be able
 * to tell "this is the answer" from "this is context" from "this is a footnote" without
 * reading the words at all.
 */
object HelionType {

    /**
     * The hero numeral, in [PlexSans] rather than a monospaced face. Monospacing gives
     * every separator a full digit cell, so a composed value read as separated blocks
     * ("6 h 18", "23:10") instead of as one figure. Nothing was lost by dropping it: IBM
     * Plex Sans's own digits are already tabular -- all ten have an identical 600-unit
     * advance -- so a column of figures still lines up, and only the characters between
     * them stopped being stretched. The width tests depend on that property; see
     * TileTextWidthTest, which measures these advances out of the shipped .ttf.
     */
    val hero: TextStyle = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 88.sp,
        lineHeight = 92.sp,
        letterSpacing = (-1).sp,
    )

    /** A tile's or the detail screen's own value. See [hero] on the figure treatment. */
    val valueLarge: TextStyle = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
    )

    /** A tile's compact value, or a stat figure sitting beside others in a row. */
    val valueMedium: TextStyle = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 26.sp,
    )

    /**
     * A small numeral drawn directly on a chart's own `Canvas` -- an axis tick, a gridline
     * value, a scrub chip's time or value readout -- rather than laid out as a Compose
     * `Text`. Set in [PlexSans] like every other figure in the app (see [hero]), whose
     * digits are tabular, so a stack of axis ticks lines up.
     */
    val axisLabel: TextStyle = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    )

    /**
     * A page-level headline: an empty state's own title, the one place this app still needs
     * a Material-style "headlineSmall" -- but in [PlexSans], not the system default face
     * Material falls back to, so an empty Accueil does not suddenly look like a different
     * app from the one either side of it.
     */
    val headline: TextStyle = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    )

    /** A section title: sentence case, [PlexSans], one clear step above a caption. */
    val title: TextStyle = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    )

    /**
     * A short word label: a metric's name on a tile, a range/window selector option, a
     * button. Sentence case, [PlexSans] -- deliberately not the uppercase, letter-spaced
     * mono style this app used to default to.
     */
    val label: TextStyle = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )

    /** A smaller word label: a unit, a stat's caption, an inline tag. Sentence case, [PlexSans]. */
    val labelSmall: TextStyle = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )

    /** Prose: freshness line, notes, empty-state copy. */
    val body: TextStyle = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    )

    /** Smaller prose: captions, secondary lines. */
    val bodySmall: TextStyle = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )
}
