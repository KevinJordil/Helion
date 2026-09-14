package ch.kevinjordil.helion.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import ch.kevinjordil.helion.ui.theme.HelionSurfacePadding
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType

/** The goal track's own thickness -- a hairline reading, never a bar chart. */
private val GOAL_TRACK_HEIGHT = 5.dp

/**
 * One tile of Accueil's grid: label, latest value and unit, and the metric's own strand
 * of the day ribbon. One metric is one idea, so each tile now sits on its own raised
 * surface (see [HomeScreen]'s own tile row). Padding belongs inside the clickable column
 * so the whole card, including the space around its text, opens the metric.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetricTile(
    metric: Metric,
    latestValue: Double?,
    ribbonBars: List<RibbonBar>,
    personalBaseline: PersonalBaseline?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    goalProgress: Float? = null,
    goalCaption: String? = null,
    strandCaption: String? = null,
) {
    val colors = HelionThemeTokens.colors
    val hue = colors.metricColor(metric)
    Column(
        modifier = modifier
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = HelionSurfacePadding, vertical = 10.dp),
    ) {
        // No maxLines/ellipsis: labels are kept short in strings.xml specifically so they
        // fit at 320dp, but if a larger system font scale ever still doesn't have room,
        // this wraps to a second line rather than clipping -- see NoTextClippingTest.
        Text(
            stringResource(metric.labelRes),
            style = HelionType.label,
            color = colors.textSecondary,
        )
        // Share a baseline when there is room; wrap the unit at larger font scales.
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                latestValue?.let { metric.formatValue(it) } ?: "—",
                style = if (metric.id == "heart_rate") HelionType.valueLarge else HelionType.valueMedium,
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
        // No strand at all rather than an empty one: a metric that has nothing to draw
        // here (see TileStrand.NONE) must not reserve a band of blank space either.
        if (ribbonBars.isNotEmpty()) {
            DayRibbon(
                bars = ribbonBars,
                barColor = hue,
                modifier = Modifier.tileRibbonSize().padding(top = 8.dp),
            )
        }
        // A goal is the one thing on a tile that has a target to be measured against, so it
        // is the one thing that gets a track rather than only a figure -- the pattern every
        // measurement app uses for exactly this, and the only reading in Helion that has a
        // target at all. Metrics with no goal show none: there is no target for a resting
        // heart rate to be a percentage of.
        if (goalProgress != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(GOAL_TRACK_HEIGHT)
                    .clip(RoundedCornerShape(GOAL_TRACK_HEIGHT / 2))
                    .background(colors.divider),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(goalProgress)
                        .height(GOAL_TRACK_HEIGHT)
                        .clip(RoundedCornerShape(GOAL_TRACK_HEIGHT / 2))
                        .background(hue),
                )
            }
        }
        // Compact context shares one line when possible; large fonts can still wrap.
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (goalCaption ?: strandCaption)?.let {
                Text(
                    it,
                    style = HelionType.labelSmall,
                    color = colors.textTertiary,
                )
            }
            personalBaseline?.let { baseline ->
                val (messageRes, isAmber) = personalBaselineCompactMessage(baseline)
                Text(
                    stringResource(messageRes),
                    style = HelionType.labelSmall,
                    color = if (isAmber) colors.accentAmber else colors.textTertiary,
                )
            }
        }
    }
}
