package ch.kevinjordil.helion.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Helion's one card radius, used everywhere a block of content is grouped onto a raised
 * surface (see [HelionSurface]) instead of being separated from its neighbours by a hairline
 * rule. One radius across the whole app, not a different rounding per screen, is what keeps
 * this reading as one material rather than a patchwork.
 */
val HelionCornerRadius: Dp = 20.dp

/** A smaller radius for a compact inline surface (a chip, a pill) that the full card radius would swallow. */
val HelionCornerRadiusSmall: Dp = 12.dp

/**
 * The one content padding inside a [HelionSurface].
 *
 * Its exact value matters: [HelionScreenEdgeMargin] plus this is the distance from the
 * screen edge at which every piece of text in the app starts. A screen's own headings sit
 * at that same distance directly, so a card whose padding differs puts its content off the
 * app's single left-hand line -- which is precisely how Accueil's tiles ended up 6dp out
 * from the hero above them.
 */
val HelionSurfacePadding: Dp = 16.dp

/**
 * The gap between a card's outer edge and the screen's. Small on purpose: cards are nearly
 * full-bleed, and the breathing room lives inside them ([HelionSurfacePadding]) rather than
 * around them.
 */
val HelionScreenEdgeMargin: Dp = 4.dp

/**
 * The distance from the screen edge to any text, in or out of a card. Screen headings,
 * back links and section titles -- everything that is not inside a [HelionSurface] -- use
 * this, so they land on the same line as the card content below them.
 */
val HelionContentInset: Dp = HelionScreenEdgeMargin + HelionSurfacePadding

/**
 * The vertical gap between two cards, and between a screen's heading block and its first
 * card. One rhythm for the whole app: the activity screens used 12dp, the list and settings
 * screens 16dp and Sommeil 24dp, which read as three different apps.
 */
val HelionCardSpacing: Dp = 16.dp

/**
 * One softly rounded, raised surface -- [HelionColors.surfaceRaised] clipped to
 * [HelionCornerRadius], with [HelionSurfacePadding] of breathing room inside it -- for
 * grouping a card's worth of related content. This is the app's answer to "surfaces, not
 * rules": a screen that used to separate its sections with a divider and tight padding
 * groups them onto one of these instead, and any future screen should reach for this rather
 * than inventing its own rounding, background or padding.
 */
@Composable
fun HelionSurface(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(HelionSurfacePadding),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = HelionThemeTokens.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(HelionCornerRadius))
            .background(colors.surfaceRaised)
            .padding(padding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}
