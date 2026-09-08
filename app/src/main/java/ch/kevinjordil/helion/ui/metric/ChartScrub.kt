package ch.kevinjordil.helion.ui.metric

import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Maps a horizontal drag position on the chart to the reading it is closest to in time --
 * the pure geometry behind the detail screen's scrub gesture, kept separate from any
 * Compose code so it is testable as a plain function.
 *
 * [xFraction] is the drag position along the chart's width, `0f` at the left edge (the
 * oldest reading) to `1f` at the right edge (the newest). Readings are assumed sorted by
 * timestamp ascending, exactly what [MetricReader.load] already returns.
 *
 * Degenerate cases:
 * - empty list: null, there is nothing to scrub to.
 * - a single reading: always that one reading, regardless of [xFraction].
 * - identical timestamps (a zero-width time span): falls back to [xFraction] scaled
 *   directly across the index range, so scrubbing still moves through the list instead of
 *   dividing by a zero span.
 */
fun scrubReading(readings: List<Reading>, xFraction: Float): Reading? {
    if (readings.isEmpty()) return null
    if (readings.size == 1) return readings.single()

    val clamped = xFraction.coerceIn(0f, 1f)
    val minT = readings.first().timestamp
    val maxT = readings.last().timestamp
    val span = maxT - minT

    if (span <= 0L) {
        val index = (clamped * (readings.size - 1)).toInt().coerceIn(0, readings.size - 1)
        return readings[index]
    }

    val targetTimestamp = minT + (clamped * span).toLong()
    return readings.minByOrNull { kotlin.math.abs(it.timestamp - targetTimestamp) }
}

/**
 * The Y-axis range a chart should scale to.
 *
 * When [zeroBased] is false (every metric except steps -- an instantaneous measurement like
 * heart rate, SpO2 or skin temperature has no meaningful zero to anchor to), scales to the
 * data's own [min]..[max] plus 20% padding on both sides, with [paddingFloor] as a minimum
 * so a near-constant series still gets visible headroom instead of a razor-thin band, and so
 * the range is never zero-width. Without this, a narrow-range series like respiratory rate
 * (roughly 12-18) or SpO2 (roughly 94-99) scaled from zero collapses into a flat line near
 * the top of the chart -- the variation is the whole point and a zero baseline erases it.
 *
 * When [zeroBased] is true (steps, a daily total where zero genuinely means "no steps that
 * day"), the bottom stays pinned at zero and only the top gets padding, so the real daily
 * comparison a zero baseline is for is preserved.
 *
 * Originally written for [ch.kevinjordil.helion.ui.sleep.SleepScreen]'s nightly respiratory
 * chart; shared here rather than duplicated for [MetricScreen]'s chart, which needed the
 * same treatment.
 */
fun chartYRange(min: Float, max: Float, zeroBased: Boolean, paddingFloor: Float = 1f): Pair<Float, Float> {
    if (zeroBased) {
        val topPadding = (max * 0.1f).let { if (it > 0f) it else paddingFloor }
        return 0f to (max + topPadding)
    }
    val padding = ((max - min) * 0.2f).let { if (it > 0f) it else paddingFloor }
    return (min - padding) to (max + padding)
}

/**
 * Round, evenly-spaced values across `[min, max]`, meant to be drawn as a chart's
 * horizontal gridlines. Picks a "nice" step -- 1, 2 or 5 times a power of ten -- close to
 * `(max - min) / targetCount` rather than dividing the range into exactly [targetCount]
 * equal, arbitrary-looking slices: a gridline at 100 or 50 is something a glance can place
 * a curve against, one at 73.4 is not.
 *
 * Degenerate cases, so a chart can call this on whatever range it has without guarding it
 * first:
 * - `max < min`, or either bound is NaN: an empty list -- there is no meaningful range to
 *   mark at all.
 * - `max == min` (every value identical, including a single reading padded to a zero-width
 *   range by an unusual caller): a single gridline at that value, rather than dividing a
 *   zero span into a step.
 *
 * [targetCount] is a target, not an exact count: the actual number of returned values
 * depends on where the nice step lands relative to `[min, max]`, typically landing within
 * one or two of [targetCount].
 */
fun chartGridlines(min: Float, max: Float, targetCount: Int = 4): List<Float> {
    if (min.isNaN() || max.isNaN() || max < min) return emptyList()
    if (max == min) return listOf(min)

    val rawStep = (max - min) / targetCount.coerceAtLeast(1)
    val step = niceStep(rawStep)
    if (step <= 0f) return listOf(min, max)

    val start = ceil(min / step) * step
    val values = mutableListOf<Float>()
    var value = start
    // A hard cap on iterations, not just the `<= max` bound: floating-point step
    // accumulation could in principle undershoot forever on a pathological input, and this
    // is what actually rules out "absurd ticks" rather than trusting the loop to terminate.
    var guard = 0
    while (value <= max + step * 1e-3f && guard <= targetCount + 4) {
        values.add(value)
        value += step
        guard++
    }
    return values
}

/** The nicest of {1, 2, 5} x a power of ten that is at least as large as [rawStep]. */
private fun niceStep(rawStep: Float): Float {
    if (rawStep <= 0f || !rawStep.isFinite()) return 0f
    val magnitude = 10f.pow(floor(log10(rawStep)))
    val residual = rawStep / magnitude
    val niceResidual = when {
        residual <= 1f -> 1f
        residual <= 2f -> 2f
        residual <= 5f -> 5f
        else -> 10f
    }
    return niceResidual * magnitude
}

/**
 * Timestamps for a chart's horizontal time axis, in `[startTimestamp, endTimestamp]`,
 * landing on boundaries appropriate to how wide that span actually is rather than on
 * arbitrary evenly-spaced fractions -- exactly what "hours across a day, days across a
 * week, dates across a month" asks for. The caller formats each returned timestamp however
 * suits the range it is showing (an hour, a weekday, a date); this function only decides
 * *where* those marks fall.
 *
 * Degenerate cases:
 * - `endTimestamp <= startTimestamp` (an empty or backwards span): an empty list.
 *
 * [zone] is the calendar the boundaries are snapped to (local midnight, local hour marks),
 * defaulting to the device's own zone like the rest of the app's date handling.
 */
fun chartTimeMarkers(startTimestamp: Long, endTimestamp: Long, zone: ZoneId = ZoneId.systemDefault()): List<Long> {
    if (endTimestamp <= startTimestamp) return emptyList()
    val spanSeconds = endTimestamp - startTimestamp
    val oneDaySeconds = 86_400L

    return when {
        spanSeconds <= AXIS_HOUR_SPAN_CEILING_SECONDS -> hourMarkers(startTimestamp, endTimestamp, zone)
        spanSeconds <= oneDaySeconds * 10 -> dayMarkers(startTimestamp, endTimestamp, zone, stepDays = 1)
        else -> {
            val spanDays = (spanSeconds / oneDaySeconds).toInt().coerceAtLeast(1)
            // Roughly five marks across the whole span, never less than a week apart --
            // a month's worth of daily marks would crowd into unreadable clutter.
            val stepDays = (spanDays / 5).coerceAtLeast(7)
            dayMarkers(startTimestamp, endTimestamp, zone, stepDays)
        }
    }
}

/** Hour-boundary marks, spaced at whichever of {1, 2, 3, 4, 6, 8, 12, 24} hours keeps the count near five. */
private fun hourMarkers(start: Long, end: Long, zone: ZoneId): List<Long> {
    val spanHours = (end - start) / 3600.0
    val stepHours = HOUR_STEPS.firstOrNull { spanHours / it <= 5.0 } ?: HOUR_STEPS.last()

    val startHourOfDay = Instant.ofEpochSecond(start).atZone(zone).hour
    val snappedHour = (startHourOfDay / stepHours) * stepHours
    var mark = Instant.ofEpochSecond(start).atZone(zone)
        .withHour(snappedHour)
        .withMinute(0)
        .withSecond(0)
        .withNano(0)
        .toEpochSecond()
    if (mark < start) mark += stepHours * 3_600L

    val marks = mutableListOf<Long>()
    while (mark <= end) {
        marks.add(mark)
        mark += stepHours * 3_600L
    }
    return marks
}

/** Local-midnight marks every [stepDays] days. */
private fun dayMarkers(start: Long, end: Long, zone: ZoneId, stepDays: Int): List<Long> {
    var date = Instant.ofEpochSecond(start).atZone(zone).toLocalDate()
    var mark = date.atStartOfDay(zone).toEpochSecond()
    if (mark < start) {
        date = date.plusDays(1)
        mark = date.atStartOfDay(zone).toEpochSecond()
    }

    val marks = mutableListOf<Long>()
    while (mark <= end) {
        marks.add(mark)
        date = date.plusDays(stepDays.toLong())
        mark = date.atStartOfDay(zone).toEpochSecond()
    }
    return marks
}

private val HOUR_STEPS = listOf(1, 2, 3, 4, 6, 8, 12, 24)

/** The span, in seconds, below which [chartTimeMarkers] places hour marks rather than day marks. */
const val AXIS_HOUR_SPAN_CEILING_SECONDS = 86_400L * 3 / 2

/**
 * Formats one of [chartTimeMarkers]' own timestamps for display: a bare hour figure
 * ("6h") for a day-long [spanSeconds], a compact day/month figure ("6/3") otherwise.
 * Deliberately not a resource string -- like [ch.kevinjordil.helion.ui.metric.MetricScreen]'s
 * own `TIMESTAMP_FORMAT` and Sommeil's `"%dh%02d"` duration format, this is a numeral
 * layout, not a translated phrase, so nothing here needs an entry in strings.xml.
 *
 * [spanSeconds] must be decided the same way [chartTimeMarkers] itself decided where to
 * place marks for that span -- passing the two different spans would show hour labels a
 * week apart or date labels four to a day.
 */
fun formatAxisTimestamp(timestamp: Long, spanSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val zoned = Instant.ofEpochSecond(timestamp).atZone(zone)
    return if (spanSeconds <= AXIS_HOUR_SPAN_CEILING_SECONDS) {
        "${zoned.hour}h"
    } else {
        "${zoned.dayOfMonth}/${zoned.monthValue}"
    }
}
