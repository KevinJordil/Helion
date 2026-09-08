package ch.kevinjordil.helion.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ch.kevinjordil.helion.R

/**
 * IBM Plex Mono is reserved for numerals now: the hero figure, a tile's or a stat's own
 * value, and the small numeric readouts a chart draws on itself (an axis tick, a scrub
 * chip's time and value). A monospaced digit is what makes those numbers read at a glance
 * instead of reflowing as they change -- that is the one place this app is still a measuring
 * instrument's dial, not a wellness app's copy.
 *
 * Every word -- a label, a caption, a section title, a button -- is set in [PlexSans]
 * instead (see [HelionType]'s own kdoc). The uppercase, letter-spaced mono label used to be
 * this app's default for that; it was also its strongest "terminal" signal, so it is retired
 * as the default rather than kept as one of two competing styles.
 *
 * Bundled as static .ttf resources rather than the downloadable-fonts API: no network
 * fetch, no first-run flash of a fallback face, and no new Gradle dependency.
 */
val PlexMono = FontFamily(
    Font(R.font.ibmplexmono_regular, FontWeight.Normal),
    Font(R.font.ibmplexmono_medium, FontWeight.Medium),
    Font(R.font.ibmplexmono_semibold, FontWeight.SemiBold),
)

/** Every word -- prose, labels, captions, section titles, buttons -- is set in IBM Plex Sans. */
val PlexSans = FontFamily(
    Font(R.font.ibmplexsans_regular, FontWeight.Normal),
    Font(R.font.ibmplexsans_medium, FontWeight.Medium),
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
    /** The hero numeral: reads like an instrument, not a headline. Numerals only -- see [PlexMono]. */
    val hero: TextStyle = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 88.sp,
        lineHeight = 92.sp,
        letterSpacing = (-1).sp,
    )

    /** A tile's or the detail screen's own value. */
    val valueLarge: TextStyle = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
    )

    /** A tile's compact value, or a stat figure sitting beside others in a row. */
    val valueMedium: TextStyle = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 26.sp,
    )

    /**
     * A small numeral drawn directly on a chart's own `Canvas` -- an axis tick, a gridline
     * value, a scrub chip's time or value readout -- rather than laid out as a Compose
     * `Text`. Kept in [PlexMono] and untracked: these are numbers, not words, so [PlexMono]'s
     * "numerals only" rule still applies to them even though they never go through [label]
     * or [labelSmall].
     */
    val axisLabel: TextStyle = TextStyle(
        fontFamily = PlexMono,
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
     * button. Sentence case, [PlexSans] -- see [PlexMono]'s kdoc for why this is no longer
     * the uppercase, letter-spaced mono style it used to be.
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
