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
 * Generous default content padding for [HelionSurface]: this is spacing standing in for the
 * hairline rules and bare columns the instrument-panel-era screens used to separate content,
 * so it is deliberately roomier than the 12-16dp paddings scattered through the older screens.
 */
val HelionSurfacePadding: Dp = 20.dp

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
