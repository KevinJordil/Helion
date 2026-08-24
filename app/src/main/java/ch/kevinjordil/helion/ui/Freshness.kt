package ch.kevinjordil.helion.ui

/**
 * How old the newest sample in the archive is, in whole minutes. Nothing in this app is
 * live -- the wristband only reports what it has whenever Gadgetbridge exports it, and
 * Helion only ever reads that export -- so the delay is always worth surfacing rather
 * than hiding.
 *
 * Measured against the newest sample actually stored, deliberately *not* against when a
 * sync was last attempted. An attempt timestamp is written on every pass including the
 * failing ones, so a sync that has been broken for a week would still report "synced 0
 * minutes ago" next to week-old numbers. The age of the data is what "last value received"
 * actually means, and it cannot claim a freshness the archive does not have.
 *
 * Null in, null out: nothing has ever been stored, so there is no age to report.
 */
fun minutesSinceLastSample(latestSampleTimestamp: Long?, nowSeconds: Long): Int? =
    latestSampleTimestamp?.let { ((nowSeconds - it) / 60).toInt().coerceAtLeast(0) }
