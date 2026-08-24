package ch.kevinjordil.helion.ui

/**
 * How long ago the strap last reported, in whole minutes. Nothing in this app is live --
 * the wristband only reports what it has whenever Gadgetbridge exports it, and Helion only
 * ever reads that export -- so the delay is always worth surfacing rather than hiding.
 *
 * Null in, null out: no sync has ever been attempted, so there is nothing to measure a
 * delay against.
 */
fun minutesSinceLastSync(lastSyncAttempt: Long?, nowSeconds: Long): Int? =
    lastSyncAttempt?.let { ((nowSeconds - it) / 60).toInt().coerceAtLeast(0) }
