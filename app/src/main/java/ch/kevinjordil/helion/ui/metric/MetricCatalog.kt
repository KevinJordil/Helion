package ch.kevinjordil.helion.ui.metric

import ch.kevinjordil.helion.R

/** Where a metric's values come from in the store. */
enum class MetricSource { HEART_RATE, STEPS, POINT_SERIES }

/**
 * How a source's raw readings should be combined before they are shown.
 *
 * Heart rate and point series (stress, SpO2, PAI, HRV, temperature) are instantaneous
 * measurements: plotting and averaging the raw readings as-is is meaningful.
 *
 * Steps are a per-minute count. "Average 1.4 steps per minute" tells a user nothing --
 * what they actually want is a daily total. [DAILY_SUM] buckets readings by calendar day
 * (UTC) and sums them, so the chart shows one point per day and the min/max/average are
 * over daily totals rather than over per-minute noise.
 */
enum class Aggregation { NONE, DAILY_SUM }

/** How a [MetricSource]'s raw readings aggregate into what the screen actually shows. */
val MetricSource.aggregation: Aggregation
    get() = if (this == MetricSource.STEPS) Aggregation.DAILY_SUM else Aggregation.NONE

/**
 * A displayable metric. [id] doubles as the PointSample.series key for POINT_SERIES
 * metrics, so the catalog and [ch.kevinjordil.helion.source.ExportReader] stay in step.
 *
 * [decimals] controls how the latest value and the stats are formatted.
 *
 * [plausibleRange], when set, excludes readings outside it before they reach the chart or
 * the stats -- the store itself stays raw, this only affects what the display layer shows.
 * See [MetricCatalog]'s temperature entry for why this exists.
 */
data class Metric(
    val id: String,
    val labelRes: Int,
    val unitRes: Int,
    val source: MetricSource,
    val decimals: Int = 0,
    val plausibleRange: ClosedFloatingPointRange<Double>? = null,
)

object MetricCatalog {

    /**
     * Skin temperature readings below this are the strap sitting on a table, not on a
     * wrist: on this device's exports, normal skin temperature runs roughly 28-40 C while
     * an off-body reading sits at room temperature, roughly 18-24 C. The two are easy to
     * tell apart, but a plain min/max over the unfiltered readings mixes both into a
     * misleading "21.9 C - 38.4 C". ExportReader keeps every raw reading -- its contract
     * is fidelity -- so the cut is made here, at the display layer, and only changes what
     * the chart and the stats show; the archive underneath stays untouched.
     */
    private const val MIN_PLAUSIBLE_SKIN_TEMPERATURE = 25.0
    private const val MAX_PLAUSIBLE_SKIN_TEMPERATURE = 42.0

    val all = listOf(
        Metric("heart_rate", R.string.metric_heart_rate, R.string.unit_bpm, MetricSource.HEART_RATE),
        Metric("steps", R.string.metric_steps, R.string.unit_steps, MetricSource.STEPS),
        Metric("stress", R.string.metric_stress, R.string.unit_none, MetricSource.POINT_SERIES),
        Metric("spo2", R.string.metric_spo2, R.string.unit_percent, MetricSource.POINT_SERIES),
        Metric("pai", R.string.metric_pai, R.string.unit_none, MetricSource.POINT_SERIES, decimals = 1),
        Metric("hrv", R.string.metric_hrv, R.string.unit_ms, MetricSource.POINT_SERIES, decimals = 1),
        Metric(
            id = "temperature",
            labelRes = R.string.metric_temperature,
            unitRes = R.string.unit_celsius,
            source = MetricSource.POINT_SERIES,
            decimals = 1,
            plausibleRange = MIN_PLAUSIBLE_SKIN_TEMPERATURE..MAX_PLAUSIBLE_SKIN_TEMPERATURE,
        ),
    )

    fun byId(id: String): Metric =
        all.firstOrNull { it.id == id } ?: error("Unknown metric: $id")
}

/** Formats [value] to this metric's configured precision, e.g. "36.5" for a temperature. */
fun Metric.formatValue(value: Double): String = "%.${decimals}f".format(value)
