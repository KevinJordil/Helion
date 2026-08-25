package ch.kevinjordil.helion.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ch.kevinjordil.helion.R

/**
 * Every numeral and short uppercase label is set in IBM Plex Mono -- this is a measuring
 * instrument's dial, not a wellness app, and a monospaced digit is what makes the hero
 * number read at a glance instead of reflowing as it changes.
 *
 * Bundled as static .ttf resources rather than the downloadable-fonts API: no network
 * fetch, no first-run flash of a fallback face, and no new Gradle dependency.
 */
val PlexMono = FontFamily(
    Font(R.font.ibmplexmono_regular, FontWeight.Normal),
    Font(R.font.ibmplexmono_medium, FontWeight.Medium),
    Font(R.font.ibmplexmono_semibold, FontWeight.SemiBold),
)

/** Prose -- copy, notes, dialog text -- is set in IBM Plex Sans. */
val PlexSans = FontFamily(
    Font(R.font.ibmplexsans_regular, FontWeight.Normal),
    Font(R.font.ibmplexsans_medium, FontWeight.Medium),
)

/**
 * Helion's type scale. Kept as a flat set of named styles rather than shoehorned into
 * Material3's full type scale -- this UI does not use most of Material's roles (no cards,
 * no filled buttons), so naming them for what they actually are here is clearer than
 * borrowing "displayLarge" etc. for meanings Material never intended.
 */
object HelionType {
    /** The hero numeral: reads like an instrument, not a headline. */
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

    /** A tile's compact value. */
    val valueMedium: TextStyle = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 26.sp,
    )

    /** Uppercase short labels: metric names, stat labels, section headers. */
    val label: TextStyle = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.5.sp,
    )

    /** A smaller uppercase label, e.g. the unit next to a value. */
    val labelSmall: TextStyle = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.sp,
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
