package ch.kevinjordil.helion.ui.metric

import ch.kevinjordil.helion.store.HelionDatabase

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

private const val SECONDS_PER_DAY = 86_400L

/**
 * Reads a metric's raw samples for a range and turns them into what the screen shows: a
 * filtered, aggregated reading list plus the stats over exactly that list. All the
 * calculation lives here so MetricScreen only ever renders a [MetricUiState].
 */
class MetricReader(private val db: HelionDatabase) {

    suspend fun load(metric: Metric, range: Range, now: Long): MetricUiState {
        val from = now - range.seconds
        val raw = rawReadings(metric, from, now)
        val plausible = metric.plausibleRange
            ?.let { bounds -> raw.filter { it.value in bounds } }
            ?: raw
        val readings = when (metric.source.aggregation) {
            Aggregation.NONE -> plausible
            Aggregation.DAILY_SUM -> dailyTotals(plausible)
        }

        return MetricUiState(
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
     * Buckets readings by UTC calendar day and sums each bucket. The bucket's timestamp is
     * the day's start, so the resulting list stays sorted and plottable like any other.
     */
    private fun dailyTotals(readings: List<Reading>): List<Reading> =
        readings
            .groupBy { dayStart(it.timestamp) }
            .toSortedMap()
            .map { (day, group) -> Reading(day, group.sumOf { it.value }) }

    private fun dayStart(timestamp: Long): Long {
        val mod = timestamp % SECONDS_PER_DAY
        return timestamp - if (mod < 0) mod + SECONDS_PER_DAY else mod
    }

    private fun statsOf(readings: List<Reading>): MetricStats? =
        readings.takeIf { it.isNotEmpty() }?.let { values ->
            MetricStats(
                min = values.minOf { it.value },
                max = values.maxOf { it.value },
                average = values.sumOf { it.value } / values.size,
            )
        }
}
