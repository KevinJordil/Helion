package ch.kevinjordil.helion.ui.ribbon

import ch.kevinjordil.helion.ui.metric.Reading

/**
 * One bar of a day ribbon: a bucket of the window that actually had a reading.
 * [xFraction] is the bucket's centre, [valueFraction] its normalised height, both in
 * `0f..1f`. A bucket with no reading in it produces no [RibbonBar] at all -- the ribbon is
 * built only from where data exists, so a gap in the list *is* the gap on screen. This is
 * deliberate: see [MetricCatalog]'s and the app's design notes on why no tile shows an age
 * -- the differing natural rhythms of the seven metrics (heart rate every minute, PAI
 * roughly every 8 hours) mean a uniform "stale after N minutes" rule would flag PAI as
 * broken all day, every day. The ribbon shows the true shape of *when* data arrived
 * instead, and a viewer reads the trailing gap at the right edge as "nothing since here"
 * without Helion ever computing or stating an age for it.
 */
data class RibbonBar(val xFraction: Float, val valueFraction: Float)

/**
 * Buckets [readings] into [bucketCount] equal slices of `[windowStart, windowEnd)`,
 * averages each occupied bucket, and normalises those averages to `0f..1f` for drawing.
 *
 * Degenerate cases, mirroring the existing chart's handling (see
 * [ch.kevinjordil.helion.ui.metric.MetricScreen]'s `LineChart`):
 * - no readings, or a non-positive window: an empty list -- nothing is drawn, which reads
 *   as one continuous gap, exactly the intended signal for "no data at all today".
 * - every bucket average identical (including just one occupied bucket): every bar gets
 *   [valueFraction] `0.5f` instead of dividing by a zero value-range.
 *
 * [bucketCount] defaults to 48 (30-minute buckets over a day): coarse enough that a tile's
 * few dozen pixels of width still show a bar per bucket, fine enough that stress's
 * ~5-minute cadence and heart rate's 1-minute cadence both read as a dense, continuous
 * strand while PAI's ~8-hourly cadence still reads as a handful of separate marks rather
 * than smearing into one block.
 */
fun buildRibbon(
    readings: List<Reading>,
    windowStart: Long,
    windowEnd: Long,
    bucketCount: Int = 48,
): List<RibbonBar> {
    val span = windowEnd - windowStart
    if (span <= 0 || bucketCount <= 0 || readings.isEmpty()) return emptyList()

    val bucketWidth = span.toDouble() / bucketCount
    val byBucket = readings
        .filter { it.timestamp in windowStart until windowEnd }
        .groupBy { reading ->
            (((reading.timestamp - windowStart) / bucketWidth).toInt()).coerceIn(0, bucketCount - 1)
        }
    if (byBucket.isEmpty()) return emptyList()

    val averages = byBucket.mapValues { (_, group) -> group.sumOf { it.value } / group.size }
    val minValue = averages.values.min()
    val maxValue = averages.values.max()
    val valueSpan = (maxValue - minValue).takeIf { it > 0.0 }

    return averages.toSortedMap().map { (bucket, average) ->
        val fraction = if (valueSpan == null) 0.5f else ((average - minValue) / valueSpan).toFloat()
        RibbonBar(
            xFraction = ((bucket + 0.5) / bucketCount).toFloat(),
            valueFraction = fraction,
        )
    }
}
