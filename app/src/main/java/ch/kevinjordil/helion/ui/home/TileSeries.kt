package ch.kevinjordil.helion.ui.home

import ch.kevinjordil.helion.ui.metric.Metric
import ch.kevinjordil.helion.ui.metric.Reading
import ch.kevinjordil.helion.ui.quality.PAI_WEEKLY_TARGET

/**
 * What a dashboard tile draws under its figure. Accueil is about now: each tile covers the
 * most recent stretch that means something for its metric (its [TileWindow]), and anything
 * older is one tap away on the metric's own screen. Within that window the drawing follows
 * what the data is (see [planTile]):
 *
 * - [LINE]: a measurement without a real zero (heart rate, temperature, SpO2, HRV,
 *   breathing) -- a line through its readings, joined across short gaps between spot
 *   readings and broken only on a long one (see [TILE_LINE_MAX_BRIDGE]).
 * - [BARS]: a score with a real zero (stress) -- bars from zero say how high each part of the
 *   window was, not just how it moved, and read just as well when only some slices have one.
 * - [GOAL]: a value with a real target (the steps goal set in Réglages, PAI's weekly 100) --
 *   a progress track towards it.
 * - [NONE]: nothing measured in the window -- the tile says how old its value is instead.
 */
enum class TileVisual { LINE, BARS, GOAL, NONE }

/**
 * The stretch a tile covers.
 *
 * - [RECENT]: the last [RECENT_WINDOW_SECONDS] (six hours), for what the strap measures all
 *   day long (heart rate, skin temperature, stress).
 * - [LAST_NIGHT]: the last night's sleep, from falling asleep to waking, for what it only
 *   measures asleep (HRV, breathing, SpO2): over the last six hours of a day those have
 *   nothing to show, while last night is their most recent meaningful reading.
 */
enum class TileWindow { RECENT, LAST_NIGHT }

/** The recent window: the last six hours. */
const val RECENT_WINDOW_SECONDS = 6 * 3_600L

/** Slices across a window: 36 for a line (10 minutes over six hours), 18 for bars. */
const val TILE_LINE_BUCKETS = 36
const val TILE_BAR_BUCKETS = 18

/**
 * The most empty slices a line is drawn across between two measured ones: two, so readings
 * up to about half an hour apart (40 minutes over a night) join into one trace.
 */
const val TILE_LINE_MAX_BRIDGE = 2

/** One bucket of a tile's series: its centre across the window and its height, both 0..1. */
data class TilePoint(val x: Float, val y: Float)

/** How a metric's tile draws, over which stretch, and on what scale. */
data class TileSpec(
    val visual: TileVisual,
    val window: TileWindow = TileWindow.RECENT,
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
    "heart_rate" -> TileSpec(TileVisual.LINE, minSpan = 30.0)
    "steps" -> TileSpec(TileVisual.GOAL)
    // From zero, up to at least 50: a calm stretch's 10-30 still reads as bars, not specks,
    // and a tense one stretches the scale up to its own peak.
    "stress" -> TileSpec(TileVisual.BARS, minSpan = 50.0, floor = 0.0)
    "spo2" -> TileSpec(TileVisual.LINE, TileWindow.LAST_NIGHT, minSpan = 4.0)
    "pai" -> TileSpec(TileVisual.GOAL, target = PAI_WEEKLY_TARGET)
    "hrv" -> TileSpec(TileVisual.LINE, TileWindow.LAST_NIGHT, minSpan = 20.0)
    "temperature" -> TileSpec(TileVisual.LINE, minSpan = 2.0)
    "respiratory_rate" -> TileSpec(TileVisual.LINE, TileWindow.LAST_NIGHT, minSpan = 4.0)
    else -> TileSpec(TileVisual.LINE)
}

/** What a tile draws ([visual]) and over which stretch ([window], from [start] to [end]). */
data class TilePlan(val visual: TileVisual, val window: TileWindow, val start: Long, val end: Long)

/**
 * Decides a tile's drawing. The window is the last six hours before [now], or [lastNight]
 * (falling asleep to waking) for a [TileWindow.LAST_NIGHT] metric -- the recent hours again
 * when no night is known yet. With nothing measured in it the tile draws no chart
 * ([TileVisual.NONE]); otherwise it draws the metric's own visual, and [TileVisual.GOAL]
 * regardless.
 */
fun planTile(spec: TileSpec, readings: List<Reading>, now: Long, lastNight: LongRange? = null): TilePlan {
    val night = lastNight?.takeIf { spec.window == TileWindow.LAST_NIGHT && it.last > it.first }
    val (window, start, end) = if (night != null) {
        Triple(TileWindow.LAST_NIGHT, night.first, night.last)
    } else {
        Triple(TileWindow.RECENT, now - RECENT_WINDOW_SECONDS, now)
    }
    fun plan(visual: TileVisual) = TilePlan(visual, window, start, end)
    if (spec.visual == TileVisual.GOAL) return plan(TileVisual.GOAL)
    if (readings.none { it.timestamp in start until end }) return plan(TileVisual.NONE)
    return plan(spec.visual)
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
