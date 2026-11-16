package ch.kevinjordil.helion.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.R

/**
 * A root screen's header: its title, an optional line of context under it (a date, a sync
 * status), and room on the right for a discreet control. Kept short so the content starts
 * high on the screen.
 */
@Composable
fun HelionScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = HelionThemeTokens.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = HelionContentInset, end = HelionScreenEdgeMargin, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title,
                style = HelionType.headline,
                color = colors.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            subtitle?.invoke()
        }
        if (trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    }
}

/**
 * A pushed screen's bar: a back chevron, the screen's title, and optional controls on the
 * right. The chevron sits on the gutter, the title on the content line.
 */
@Composable
fun HelionTopBar(
    title: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    leadingTint: Color? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = HelionThemeTokens.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = HelionScreenEdgeMargin - 8.dp, end = HelionScreenEdgeMargin - 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                HelionIcons.ChevronLeft,
                contentDescription = stringResource(R.string.action_back),
                tint = colors.textPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = leadingTint ?: colors.textSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        if (title != null) {
            Text(
                title,
                style = HelionType.title.copy(fontSize = HelionType.title.fontSize * 1.06f),
                color = colors.textPrimary,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        trailing?.invoke(this)
    }
}

/**
 * A back affordance for a pushed screen whose content scrolls under its own heading: the
 * chevron and the word, as one 44dp-tall target.
 */
@Composable
fun HelionBackLink(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(HelionCornerRadiusSmall))
            .clickable(onClick = onBack, role = Role.Button)
            .heightIn(min = 44.dp)
            .padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(HelionIcons.ChevronLeft, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(22.dp))
        Text(stringResource(R.string.action_back), style = HelionType.label, color = colors.textSecondary)
    }
}

/** A section heading in capitals above a group of cards, on the content line. */
@Composable
fun HelionSectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val colors = HelionThemeTokens.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = HelionSurfacePadding).padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = HelionType.overline,
            color = colors.textTertiary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.invoke(this)
    }
}

/**
 * A compact segmented control: a dim track and a raised segment that slides to the
 * selection. Each option shares the width equally, so the control never reflows as the
 * selection changes.
 */
@Composable
fun HelionSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HelionThemeTokens.colors
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(HelionControlRadius))
            .background(colors.surfaceDim)
            .border(1.dp, colors.hairline, RoundedCornerShape(HelionControlRadius))
            .padding(3.dp),
    ) {
        val segmentWidth = maxWidth / options.size.coerceAtLeast(1)
        val offset by animateDpAsState(
            segmentWidth * selectedIndex.coerceIn(0, options.lastIndex.coerceAtLeast(0)),
            animationSpec = tween(HelionMotion.SHORT + 60, easing = HelionMotion.Emphasized),
            label = "segment",
        )
        // A negative index means nothing is chosen yet (an unset profile field): no segment.
        if (selectedIndex >= 0) {
            Box(
                modifier = Modifier
                    .offset(x = offset)
                    .width(segmentWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(HelionCornerRadiusSmall - 1.dp))
                    .background(colors.surfaceRaised),
            )
        }
        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val textColor by animateColorAsState(
                    if (selected) colors.textPrimary else colors.textSecondary,
                    animationSpec = tween(HelionMotion.SHORT),
                    label = "segment-text",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(HelionCornerRadiusSmall - 1.dp))
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = HelionType.label, color = textColor, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** The app's primary action button: accent fill, control radius, 44dp tall. */
@Composable
fun HelionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = HelionThemeTokens.colors
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        enabled = enabled,
        shape = RoundedCornerShape(HelionControlRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = colors.onAccent,
            disabledContainerColor = colors.surfaceRaised,
            disabledContentColor = colors.textTertiary,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        content()
    }
}

/** A secondary action: an outlined button in the neutral border colour. */
@Composable
fun HelionOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = HelionThemeTokens.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        enabled = enabled,
        shape = RoundedCornerShape(HelionControlRadius),
        border = BorderStroke(1.dp, if (enabled) colors.divider else colors.hairline),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = colors.surfaceDim,
            contentColor = colors.textPrimary,
            disabledContentColor = colors.textTertiary,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        content()
    }
}

/** A low-emphasis action: text in the accent colour. */
@Composable
fun HelionTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = HelionThemeTokens.colors
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(HelionControlRadius),
        colors = ButtonDefaults.textButtonColors(contentColor = colors.accent, disabledContentColor = colors.textTertiary),
        content = content,
    )
}

/** A small filled dot carrying a state: connected, needs attention, inactive. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 6.dp) {
    Box(modifier = modifier.size(size).clip(CircleShape).background(color))
}

/**
 * A short contextual reading -- "Habituel", "Plus haut", "Encore tôt" -- with a dot that
 * carries its state: neutral when it is ordinary, [HelionColors.attention] when notable.
 */
@Composable
fun ContextTag(text: String, attention: Boolean, modifier: Modifier = Modifier, dotColor: Color? = null) {
    val colors = HelionThemeTokens.colors
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusDot(if (attention) colors.attention else dotColor ?: colors.textTertiary)
        Text(text, style = HelionType.labelSmall, color = if (attention) colors.attention else colors.textSecondary)
    }
}

/** An icon on a small dim tile: a settings row's glyph, an activity's sport. */
@Composable
fun IconTile(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 34.dp, iconSize: Dp = 18.dp) {
    Box(modifier = modifier.size(size).helionWell(HelionCornerRadiusSmall + 1.dp), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/**
 * A toggle for a chart overlay, shown as a chip: a coloured key that also tells which line
 * on the chart it controls, filled when on.
 */
@Composable
fun HelionToggleChip(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, keyColor: Color, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    val background by animateColorAsState(if (checked) colors.surfaceRaised else colors.surfaceDim, tween(HelionMotion.SHORT), label = "chip")
    Row(
        modifier = modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(HelionCornerRadiusSmall))
            .background(background)
            .border(1.dp, if (checked) colors.divider else colors.hairline, RoundedCornerShape(HelionCornerRadiusSmall))
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 12.dp, height = 3.dp)
                .clip(CircleShape)
                .background(if (checked) keyColor else colors.textTertiary.copy(alpha = 0.5f)),
        )
        Text(label, style = HelionType.labelSmall, color = if (checked) colors.textPrimary else colors.textSecondary)
    }
}

/** A thin vertical rule between stats sharing a row. */
@Composable
fun VerticalRule(modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    Box(modifier = modifier.width(1.dp).fillMaxHeight().padding(vertical = 4.dp).background(colors.hairline))
}

/** A thin horizontal rule inside a card. */
@Composable
fun HorizontalRule(modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    Box(modifier = modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
}

/**
 * A figure with its unit on the same baseline when there is room, the unit wrapping under
 * it at large font scales rather than pushing the figure out of its column.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FigureWithUnit(
    value: String,
    unit: String?,
    valueStyle: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    unitStyle: androidx.compose.ui.text.TextStyle = HelionType.labelSmall,
    centred: Boolean = false,
) {
    val colors = HelionThemeTokens.colors
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp, if (centred) Alignment.CenterHorizontally else Alignment.Start),
    ) {
        Text(value, style = valueStyle, color = valueColor ?: colors.textPrimary, modifier = Modifier.alignByBaseline())
        if (!unit.isNullOrEmpty()) {
            Text(unit, style = unitStyle, color = colors.textSecondary, modifier = Modifier.alignByBaseline())
        }
    }
}

/** A clickable row with the standard touch height, for lists inside a card. */
fun Modifier.helionRow(onClick: () -> Unit): Modifier =
    this.fillMaxWidth().clickable(onClick = onClick, role = Role.Button).heightIn(min = HelionTouchTarget)
