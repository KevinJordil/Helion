package ch.kevinjordil.helion.ui.metric

import androidx.compose.ui.graphics.Color
import ch.kevinjordil.helion.ui.theme.HelionColors

/**
 * Fixed, permanent order a metric id resolves its hue by -- see [HelionColors.metricHues]
 * for the eight values themselves. This list, not [MetricCatalog.all]'s order, is the
 * source of truth: [MetricCatalog.all] is free to be reordered (a new metric inserted
 * anywhere, an existing one moved) without ever changing which colour an existing metric
 * has, because [metricColor] looks a metric up by [Metric.id] here rather than by its
 * position in any other list.
 *
 * Orange (`temperature`, the seventh entry) is deliberately not adjacent to `pai` in this
 * list -- see [ch.kevinjordil.helion.ui.theme.MetricPalette]'s kdoc on why orange belongs
 * to skin temperature specifically, not to a metric with its own reference axis that
 * already turns amber often.
 */
private val METRIC_HUE_ORDER = listOf(
    "heart_rate",
    "hrv",
    "stress",
    "spo2",
    "pai",
    "steps",
    "temperature",
    "respiratory_rate",
)

/**
 * This metric's permanent colour identity, resolved from [Metric.id] rather than from
 * where [metric] happens to sit in whatever list the caller is iterating -- see
 * [METRIC_HUE_ORDER]'s own kdoc. Every metric in [MetricCatalog.all] has an entry here;
 * an unmapped id is a bug in this list, not a state a real metric can reach, so it fails
 * loudly rather than silently falling back to some default hue.
 */
fun HelionColors.metricColor(metric: Metric): Color {
    val index = METRIC_HUE_ORDER.indexOf(metric.id)
    check(index >= 0) { "no colour assigned for metric id: ${metric.id}" }
    return metricHues[index]
}
