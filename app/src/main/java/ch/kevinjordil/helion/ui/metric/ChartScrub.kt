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
