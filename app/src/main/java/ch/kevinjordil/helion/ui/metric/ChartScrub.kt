package ch.kevinjordil.helion.ui.metric

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
