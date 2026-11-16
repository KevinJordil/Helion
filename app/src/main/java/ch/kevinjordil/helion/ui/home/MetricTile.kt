package ch.kevinjordil.helion.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.ui.metric.Metric
import ch.kevinjordil.helion.ui.metric.formatValue
import ch.kevinjordil.helion.ui.metric.metricColor
import ch.kevinjordil.helion.ui.quality.PersonalBaseline
import ch.kevinjordil.helion.ui.quality.personalBaselineCompactMessage
import ch.kevinjordil.helion.ui.theme.AnimatedFigure
import ch.kevinjordil.helion.ui.theme.ContextTag
import ch.kevinjordil.helion.ui.theme.HelionIcons
import ch.kevinjordil.helion.ui.theme.HelionSurface
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType

/** Height of a tile's chart band, the same for every visual so the grid stays even. */
private val TILE_CHART_HEIGHT = 26.dp

/** The glyph that identifies a metric, beside its name on a tile and on its detail screen. */
fun metricIcon(metric: Metric): ImageVector = when (metric.id) {
    "heart_rate" -> HelionIcons.HeartPulse
    "steps" -> HelionIcons.Footprints
    "stress" -> HelionIcons.Gauge
    "spo2" -> HelionIcons.Droplet
    "pai" -> HelionIcons.Bolt
    "hrv" -> HelionIcons.Waveform
    "temperature" -> HelionIcons.Thermometer
    "respiratory_rate" -> HelionIcons.Wind
    else -> HelionIcons.Waveform
}

/** What a tile shows under its figure, already computed from stored readings. */
sealed interface TileChart {
    data class Line(val points: List<TilePoint?>) : TileChart
    data class Bars(val points: List<TilePoint?>) : TileChart
    data class Goal(val fraction: Float, val caption: String) : TileChart
    data object None : TileChart
}

/**
 * One tile of Accueil's grid, in the "performance" register: the metric's glyph and name,
 * its latest value as the dominant element with the unit set small beside it, a chart band
 * chosen for the kind of data it is (see [TileVisual]), and a short contextual reading
 * against the owner's own recent history. The whole card opens the metric.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetricTile(
    metric: Metric,
    latestValue: Double?,
    chart: TileChart,
    personalBaseline: PersonalBaseline?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    chartCaption: String? = null,
) {
    val colors = HelionThemeTokens.colors
    val hue = colors.metricColor(metric)
    HelionSurface(
        modifier = modifier,
        padding = PaddingValues(start = 12.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(metricIcon(metric), contentDescription = null, tint = hue, modifier = Modifier.size(15.dp))
            // No maxLines: at a large font scale a label wraps rather than being clipped
            // (see NoTextClippingTest).
            Text(stringResource(metric.labelRes), style = HelionType.labelSmall, color = colors.textSecondary, modifier = Modifier.weight(1f))
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (latestValue != null) {
                AnimatedFigure(
                    value = latestValue,
                    format = metric::formatValue,
                    style = HelionType.valueLarge,
                    color = colors.textPrimary,
                    modifier = Modifier.alignByBaseline(),
                )
            } else {
                Text("—", style = HelionType.valueLarge, color = colors.textTertiary, modifier = Modifier.alignByBaseline())
            }
            val unit = stringResource(metric.unitRes)
            if (unit.isNotEmpty() && latestValue != null) {
                Text(unit, style = HelionType.labelSmall, color = colors.textSecondary, modifier = Modifier.alignByBaseline())
            }
        }
        Spacer(Modifier.weight(1f))
        Box(modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(TILE_CHART_HEIGHT), contentAlignment = Alignment.BottomStart) {
            when (chart) {
                is TileChart.Line -> TileSparkline(chart.points, hue, Modifier.fillMaxWidth().height(TILE_CHART_HEIGHT))
                is TileChart.Bars -> TileBars(chart.points, hue, Modifier.fillMaxWidth().height(TILE_CHART_HEIGHT))
                is TileChart.Goal -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(chart.caption, style = HelionType.labelSmall, color = colors.textSecondary)
                    GoalTrack(chart.fraction, hue)
                }
                TileChart.None -> Unit
            }
        }
        // The contextual reading on the left, the window the chart covers on the right.
        // The contextual reading on the left, the window the chart covers on the right. Both
        // are kept short ("Plus bas", "7 j") so they share the line even on a 320dp screen.
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            personalBaseline?.let { baseline ->
                val (messageRes, notable) = personalBaselineCompactMessage(baseline)
                ContextTag(stringResource(messageRes), attention = notable, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.width(6.dp))
            chartCaption?.let { Text(it, style = HelionType.labelSmall, color = colors.textTertiary) }
        }
    }
}
