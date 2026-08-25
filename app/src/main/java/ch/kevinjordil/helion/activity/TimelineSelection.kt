package ch.kevinjordil.helion.activity

/** A dragged-out range on the day timeline, in Unix seconds. [end] is never before [start]. */
data class TimelineSelection(val start: Long, val end: Long) {
    init {
        require(end >= start) { "end ($end) must not be before start ($start)" }
    }

    val durationSeconds: Long get() = end - start
}

/**
 * Maps two horizontal drag fractions along the day timeline to a concrete [TimelineSelection]
 * -- the pure geometry behind the manual-creation gesture, kept separate from any Compose code
 * so it is testable as a plain function, the same split [ch.kevinjordil.helion.ui.metric.scrubReading]
 * uses for the single-point scrub gesture this timeline's drag replaces with a range.
 *
 * [anchorFraction] and [currentFraction] are each `0f` at the window's left edge
 * ([windowStart]) to `1f` at its right edge ([windowEnd]), both clamped into that range first
 * -- a drag that overshoots the canvas on either side still resolves to a selection that ends
 * exactly at the window's edge, not one that is silently dropped or extrapolated past it. This
 * is the "including both edges" case: `fraction = 0f` must map to exactly [windowStart] and
 * `fraction = 1f` to exactly [windowEnd], with no off-by-one rounding either way.
 *
 * The two fractions are not assumed ordered -- a drag can go left-to-right or right-to-left --
 * so the smaller resolved instant becomes [TimelineSelection.start] and the larger becomes
 * [TimelineSelection.end] regardless of which fraction was the drag's start and which was its
 * current position.
 *
 * Returns null for a degenerate window ([windowEnd] not strictly after [windowStart]): there is
 * no meaningful fraction-to-instant mapping to make.
 */
fun selectionRange(
    windowStart: Long,
    windowEnd: Long,
    anchorFraction: Float,
    currentFraction: Float,
): TimelineSelection? {
    if (windowEnd <= windowStart) return null

    val a = instantAt(windowStart, windowEnd, anchorFraction)
    val b = instantAt(windowStart, windowEnd, currentFraction)
    return TimelineSelection(start = minOf(a, b), end = maxOf(a, b))
}

private fun instantAt(windowStart: Long, windowEnd: Long, fraction: Float): Long {
    val clamped = fraction.coerceIn(0f, 1f)
    val span = windowEnd - windowStart
    return windowStart + (clamped * span).toLong()
}
