package ch.kevinjordil.helion.ui.home

import ch.kevinjordil.helion.ui.metric.Metric
import ch.kevinjordil.helion.ui.metric.Reading
import ch.kevinjordil.helion.ui.quality.PAI_WEEKLY_TARGET

/**
 * What a dashboard tile draws under its figure. Chosen per metric for what the data is,
 * not for variety:
 *
 * - [LINE_DAY]: a continuous measurement sampled through the day (heart rate, SpO2, skin
 *   temperature) -- its shape over the last 24 hours is a line.
 * - [BARS_DAY]: a score on a fixed 0-100 scale (stress) -- bars from zero say how high each
 *   part of the day was, not just how it moved.
 * - [BARS_WEEK]: a value recorded once a night (HRV, respiratory rate) -- one bar per day
 *   over the last week is the only window with more than a single mark in it.
 * - [GOAL]: a value with a real target (the steps goal set in Réglages, PAI's weekly 100) --
 *   a progress track towards it.
 */
enum class TileVisual { LINE_DAY, BARS_DAY, BARS_WEEK, GOAL }

/** One bucket of a tile's series: its centre across the window and its height, both 0..1. */
data class TilePoint(val x: Float, val y: Float)

/** How a metric's tile draws, and the scale it draws on. */
data class TileSpec(
    val visual: TileVisual,
    /**
     * The smallest value range the tile's height stands for, so a calm series is drawn
     * calm: without it a heart rate wandering between 61 and 64 bpm would fill the tile
     * as dramatically as an interval session.
     */
    val minSpan: Double = 0.0,
    /** A fixed bottom of the scale, for a series whose zero is real (a 0-100 score). */
    val floor: Double? = null,
    /** The target a [TileVisual.GOAL] tile measures against, when it is not the steps goal. */
    val target: Double? = null,
)

fun tileSpec(metric: Metric): TileSpec = when (metric.id) {
    "heart_rate" -> TileSpec(TileVisual.LINE_DAY, minSpan = 30.0)
    "steps" -> TileSpec(TileVisual.GOAL)
    "stress" -> TileSpec(TileVisual.BARS_DAY, minSpan = 100.0, floor = 0.0)
    "spo2" -> TileSpec(TileVisual.LINE_DAY, minSpan = 6.0)
    "pai" -> TileSpec(TileVisual.GOAL, target = PAI_WEEKLY_TARGET)
    "hrv" -> TileSpec(TileVisual.BARS_WEEK, minSpan = 20.0)
    "temperature" -> TileSpec(TileVisual.LINE_DAY, minSpan = 2.0)
    "respiratory_rate" -> TileSpec(TileVisual.BARS_WEEK, minSpan = 6.0)
    else -> TileSpec(TileVisual.LINE_DAY)
}

/**
 * Buckets [readings] into [buckets] equal slices of `[windowStart, windowEnd)` and averages
 * each one. A slice with no reading is null, so a line breaks and a bar is missing exactly
 * where nothing was measured -- the series never invents a value between two readings.
 *
 * Heights are scaled to the occupied buckets' own range, widened to at least
 * [TileSpec.minSpan] around its centre, or anchored at [TileSpec.floor] when the scale has a
 * real zero. A single occupied bucket or a perfectly flat series sits on the middle line.
 */
fun tileSeries(readings: List<Reading>, windowStart: Long, windowEnd: Long, buckets: Int, spec: TileSpec): List<TilePoint?> {
    val span = windowEnd - windowStart
    if (span <= 0 || buckets <= 0) return emptyList()
    val width = span.toDouble() / buckets
    val sums = DoubleArray(buckets)
    val counts = IntArray(buckets)
    readings.forEach { reading ->
        if (reading.timestamp in windowStart until windowEnd) {
            val index = ((reading.timestamp - windowStart) / width).toInt().coerceIn(0, buckets - 1)
            sums[index] += reading.value
            counts[index]++
        }
    }
    val averages = (0 until buckets).map { if (counts[it] == 0) null else sums[it] / counts[it] }
    val present = averages.filterNotNull()
    if (present.isEmpty()) return List(buckets) { null }

    val low = present.min()
    val high = present.max()
    val floor = spec.floor
    val (base, range) = if (floor != null) {
        floor to maxOf(high - floor, spec.minSpan).takeIf { it > 0.0 }
    } else {
        val range = maxOf(high - low, spec.minSpan)
        (low - (range - (high - low)) / 2.0) to range.takeIf { it > 0.0 }
    }
    return averages.mapIndexed { index, average ->
        average?.let {
            val y = if (range == null) 0.5f else ((it - base) / range).toFloat().coerceIn(0f, 1f)
            TilePoint(x = ((index + 0.5) / buckets).toFloat(), y = y)
        }
    }
}
