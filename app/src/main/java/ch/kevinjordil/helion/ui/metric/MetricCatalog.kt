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
 * See [MetricCatalog]'s temperature and heart_rate entries for why this exists.
 *
 * [noteRes], when set, is a one-line explanation shown under the chart, so a metric whose
 * numbers have been aggregated or filtered says so on its own screen.
 *
 * [descriptionRes], when set, is the owner-approved one- or two-sentence explanation of
 * what the metric actually measures, shown on its detail screen. Wording is fixed and
 * must not be paraphrased -- it was reviewed and approved as written.
 */
data class Metric(
    val id: String,
    val labelRes: Int,
    val unitRes: Int,
    val source: MetricSource,
    val decimals: Int = 0,
    val plausibleRange: ClosedFloatingPointRange<Double>? = null,
    val noteRes: Int? = null,
    val descriptionRes: Int? = null,
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

    /**
     * Huami reports 255 bpm when the sensor did not measure anything, and a real export
     * carries such rows. Left alone, the sentinel becomes the "Max" on the first screen the
     * owner sees and stretches the chart's y-axis until the real signal is a flat line.
     * ExportReader now drops it at ingestion (same 10-224 bound Gadgetbridge uses), but rows
     * archived by an earlier version are already in the store and are never re-read -- the
     * watermarks have moved past them -- so the same bound is applied again here, where it
     * covers the existing archive too.
     */
    private const val MIN_PLAUSIBLE_HEART_RATE = 10.0
    private const val MAX_PLAUSIBLE_HEART_RATE = 224.0

    val all = listOf(
        Metric(
            id = "heart_rate",
            labelRes = R.string.metric_heart_rate,
            unitRes = R.string.unit_bpm,
            source = MetricSource.HEART_RATE,
            plausibleRange = MIN_PLAUSIBLE_HEART_RATE..MAX_PLAUSIBLE_HEART_RATE,
            noteRes = R.string.heart_rate_unmeasured_note,
            descriptionRes = R.string.metric_description_heart_rate,
        ),
        Metric(
            id = "steps",
            labelRes = R.string.metric_steps,
            unitRes = R.string.unit_steps,
            source = MetricSource.STEPS,
            noteRes = R.string.steps_daily_total_note,
            descriptionRes = R.string.metric_description_steps,
        ),
        Metric(
            "stress", R.string.metric_stress, R.string.unit_none, MetricSource.POINT_SERIES,
            descriptionRes = R.string.metric_description_stress,
        ),
        Metric(
            "spo2", R.string.metric_spo2, R.string.unit_percent, MetricSource.POINT_SERIES,
            descriptionRes = R.string.metric_description_spo2,
        ),
        Metric(
            "pai", R.string.metric_pai, R.string.unit_none, MetricSource.POINT_SERIES, decimals = 1,
            descriptionRes = R.string.metric_description_pai,
        ),
        Metric(
            "hrv", R.string.metric_hrv, R.string.unit_ms, MetricSource.POINT_SERIES, decimals = 1,
            descriptionRes = R.string.metric_description_hrv,
        ),
        Metric(
            id = "temperature",
            labelRes = R.string.metric_temperature,
            unitRes = R.string.unit_celsius,
            source = MetricSource.POINT_SERIES,
            decimals = 1,
            plausibleRange = MIN_PLAUSIBLE_SKIN_TEMPERATURE..MAX_PLAUSIBLE_SKIN_TEMPERATURE,
            noteRes = R.string.temperature_off_body_note,
            descriptionRes = R.string.metric_description_temperature,
        ),
        Metric(
            id = "respiratory_rate",
            labelRes = R.string.metric_respiratory_rate,
            unitRes = R.string.unit_breaths_per_minute,
            source = MetricSource.POINT_SERIES,
            descriptionRes = R.string.metric_description_respiratory_rate,
        ),
    )

    /**
     * Null for an unknown id rather than throwing. The id comes back from saved instance
     * state, which outlives the process and therefore outlives this catalog: dropping a
     * metric in a later version would otherwise turn every restored process into a crash
     * loop the owner cannot escape from, since the same saved state is restored each time.
     */
    fun byId(id: String): Metric? = all.firstOrNull { it.id == id }
}

/** Formats [value] to this metric's configured precision, e.g. "36.5" for a temperature. */
fun Metric.formatValue(value: Double): String = "%.${decimals}f".format(value)
