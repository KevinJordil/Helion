package ch.kevinjordil.helion.ui.metric

import ch.kevinjordil.helion.store.HelionDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** A metric's value at a point in time, ready to plot. */
data class Reading(val timestamp: Long, val value: Double)

data class MetricStats(val min: Double, val max: Double, val average: Double)

data class MetricUiState(
    val readings: List<Reading> = emptyList(),
    val latest: Reading? = null,
    val stats: MetricStats? = null,
)

enum class Range(val seconds: Long) {
    DAY(86_400),
    WEEK(7 * 86_400),
    MONTH(30 * 86_400),
    YEAR(365 * 86_400),
}

/**
 * Reads a metric's raw samples for a range and turns them into what the screen shows: a
 * filtered, aggregated reading list plus the stats over exactly that list. All the
 * calculation lives here so MetricScreen only ever renders a [MetricUiState].
 *
 * [zone] is the calendar used to bucket daily aggregates (see [dailyTotals]). It defaults
 * to the device's zone; tests pass a fixed [ZoneId] so they do not depend on the machine
 * running them.
 *
 * [dispatcher] is where the work happens. Room already runs the queries off the caller's
 * thread, but the filtering, bucketing and statistics ran wherever [load] was called from,
 * which for both screens is the main thread -- a year of minute samples is hundreds of
 * thousands of rows to map there. Everything is moved off it here.
 */
class MetricReader(
    private val db: HelionDatabase,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    suspend fun load(metric: Metric, range: Range, now: Long): MetricUiState = withContext(dispatcher) {
        // A daily aggregate must start on a day boundary. Starting exactly one range-length
        // ago cuts the oldest day in half, and that clipped bucket is then almost always the
        // minimum: "Min 294 pas" on a day the owner walked six thousand. Snapping back to
        // that day's local midnight makes the first bucket a whole day like every other.
        val from = when (metric.source.aggregation) {
            Aggregation.NONE -> now - range.seconds
            Aggregation.DAILY_SUM -> dayStart(now - range.seconds)
        }
        val raw = rawReadings(metric, from, now)
        val plausible = metric.plausibleRange
            ?.let { bounds -> raw.filter { it.value in bounds } }
            ?: raw
        val readings = when (metric.source.aggregation) {
            Aggregation.NONE -> plausible
            Aggregation.DAILY_SUM -> dailyTotals(plausible)
        }

        MetricUiState(
            readings = readings,
            latest = readings.lastOrNull(),
            stats = statsOf(readings),
        )
    }

    private suspend fun rawReadings(metric: Metric, from: Long, to: Long): List<Reading> =
        when (metric.source) {
            MetricSource.HEART_RATE -> db.minuteSamples().between(from, to)
                .mapNotNull { sample -> sample.heartRate?.let { Reading(sample.timestamp, it.toDouble()) } }

            MetricSource.STEPS -> db.minuteSamples().between(from, to)
                .mapNotNull { sample -> sample.steps?.let { Reading(sample.timestamp, it.toDouble()) } }

            MetricSource.POINT_SERIES -> db.pointSamples().between(metric.id, from, to)
                .map { Reading(it.timestamp, it.value) }
        }

    /**
     * Buckets readings by [zone]'s calendar day and sums each bucket. The bucket's
     * timestamp is that day's local midnight, so the resulting list stays sorted and
     * plottable like any other.
     *
     * Deliberately goes through [Instant]/[ZoneId] rather than a fixed 86_400-second
     * modulo: a fixed step buckets by UTC days, which silently disagrees with the
     * device's -- and Gadgetbridge's -- local calendar day by up to the zone's UTC
     * offset, shifting further still across a DST transition. Steps are the one metric
     * an owner can eyeball against the strap directly, so getting the day boundary wrong
     * here is far more visible, and more damaging to trust in every other number on the
     * screen, than the same slip in any other series.
     */
    private fun dailyTotals(readings: List<Reading>): List<Reading> =
        readings
            .groupBy { dayStart(it.timestamp) }
            .toSortedMap()
            .map { (day, group) -> Reading(day, group.sumOf { it.value }) }

    private fun dayStart(timestamp: Long): Long =
        Instant.ofEpochSecond(timestamp)
            .atZone(zone)
            .toLocalDate()
            .atStartOfDay(zone)
            .toEpochSecond()

    private fun statsOf(readings: List<Reading>): MetricStats? =
        readings.takeIf { it.isNotEmpty() }?.let { values ->
            MetricStats(
                min = values.minOf { it.value },
                max = values.maxOf { it.value },
                average = values.sumOf { it.value } / values.size,
            )
        }
}
