package ch.kevinjordil.helion.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.ui.metric.Metric
import ch.kevinjordil.helion.ui.metric.formatValue
import ch.kevinjordil.helion.ui.metric.metricColor
import ch.kevinjordil.helion.ui.quality.PersonalBaseline
import ch.kevinjordil.helion.ui.quality.personalBaselineCompactMessage
import ch.kevinjordil.helion.ui.ribbon.DayRibbon
import ch.kevinjordil.helion.ui.ribbon.RibbonBar
import ch.kevinjordil.helion.ui.ribbon.tileRibbonSize
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType

/**
 * One tile of Accueil's grid: label, latest value and unit, and the metric's own strand
 * of the day ribbon. One metric is one idea, so each tile now sits on its own raised
 * surface (see [HomeScreen]'s own tile row) rather than sharing a rectangle with its
 * neighbour; the tile's own padding lives on that surface, not here, so this content
 * column adds none of its own.
 */
@Composable
fun MetricTile(
    metric: Metric,
    latestValue: Double?,
    ribbonBars: List<RibbonBar>,
    personalBaseline: PersonalBaseline?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HelionThemeTokens.colors
    val hue = colors.metricColor(metric)
    Column(
        modifier = modifier
            .clickable(onClick = onClick, role = Role.Button),
    ) {
        // No maxLines/ellipsis: labels are kept short in strings.xml specifically so they
        // fit at 320dp, but if a larger system font scale ever still doesn't have room,
        // this wraps to a second line rather than clipping -- see NoTextClippingTest.
        Text(
            stringResource(metric.labelRes),
            style = HelionType.label,
            color = colors.textSecondary,
        )
        // Baseline-aligned, not top-aligned: a Row's default puts the small unit against
        // the top of the tall figure, so the unit floated above the number on every tile.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                latestValue?.let { metric.formatValue(it) } ?: "—",
                style = HelionType.valueMedium,
                color = if (latestValue != null) hue else colors.textTertiary,
                modifier = Modifier.alignByBaseline(),
            )
            val unit = stringResource(metric.unitRes)
            if (unit.isNotEmpty()) {
                Text(
                    unit,
                    style = HelionType.labelSmall,
                    color = colors.textTertiary,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        DayRibbon(
            bars = ribbonBars,
            barColor = hue,
            modifier = Modifier.tileRibbonSize().padding(top = 8.dp),
        )
        personalBaseline?.let { baseline ->
            // The compact form (see strings.xml), not the full sentence used on the
            // detail screen: a tile is the narrowest container in the app, and the
            // caption was the string that first got reported clipped there.
            val (messageRes, isAmber) = personalBaselineCompactMessage(baseline)
            Text(
                stringResource(messageRes),
                style = HelionType.labelSmall,
                color = if (isAmber) colors.accentAmber else colors.textTertiary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
