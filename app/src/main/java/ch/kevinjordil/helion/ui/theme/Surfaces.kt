package ch.kevinjordil.helion.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Spacing runs on a 2dp grid with 4dp as the usual step: 4, 8, 10, 12, 14, 16, 20, 24.

/** The card radius, used by every card in the app. */
val HelionCornerRadius: Dp = 14.dp

/** A control's radius: a button, a segmented control, a field. */
val HelionControlRadius: Dp = 10.dp

/** A compact inline element's radius: a chip, a status pill, a segment inside a track. */
val HelionCornerRadiusSmall: Dp = 8.dp

/**
 * The content padding inside a card. [HelionScreenEdgeMargin] plus this is
 * [HelionContentInset], the line every piece of text on a screen starts on.
 */
val HelionSurfacePadding: Dp = 14.dp

/** The outer gutter between the cards and the display edge. */
val HelionScreenEdgeMargin: Dp = 14.dp

/**
 * The distance from the screen edge to any text, in or out of a card. Headings and section
 * labels outside a card use it, so they line up with the card content below them.
 */
val HelionContentInset: Dp = HelionScreenEdgeMargin + HelionSurfacePadding

/** The gap between two cards, and between a screen's header and its first card. */
val HelionCardSpacing: Dp = 10.dp

/** The gap between two groups of cards that belong to different sections. */
val HelionSectionSpacing: Dp = 22.dp

/** How thick a chart's data line is drawn. */
val CHART_LINE_WIDTH: Dp = 2.dp

/** The minimum height of anything tappable. */
val HelionTouchTarget: Dp = 48.dp

/**
 * One card: [HelionColors.surface] on the black ground, [HelionCornerRadius] corners, a
 * hairline edge that keeps the card's outline crisp against pure black, and no shadow or
 * wash. Every grouped block of content in the app sits on one of these.
 *
 * With [onClick] the whole card is the touch target, ripple included.
 */
@Composable
fun HelionSurface(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(HelionSurfacePadding),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = HelionThemeTokens.colors
    val shape = RoundedCornerShape(HelionCornerRadius)
    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.hairline, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .padding(padding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/** A secondary surface directly on the ground or inside a card: a chart's well, an icon tile. */
@Composable
fun Modifier.helionWell(radius: Dp = HelionCornerRadiusSmall): Modifier {
    val colors = HelionThemeTokens.colors
    return this.clip(RoundedCornerShape(radius)).background(colors.surfaceDim)
}
