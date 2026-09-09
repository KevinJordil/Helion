package ch.kevinjordil.helion.ui.home

/**
 * How far a day's total has got towards its goal, clamped to `0f..1f`.
 *
 * Clamped on purpose: the bar this feeds is a "how close am I" reading, and a bar drawn
 * past its own track is a rendering bug rather than extra information. Beating a goal is
 * still said outright -- the figure itself is always on screen next to the bar, and the
 * caption names the goal -- so nothing is hidden by the clamp.
 *
 * A goal of zero or less has no meaning to be a fraction of, so it returns null and the
 * caller shows no bar at all rather than a full or empty one, either of which would be a
 * statement the archive cannot support.
 */
fun goalProgressFraction(total: Double?, goal: Int): Float? {
    if (total == null || goal <= 0) return null
    return (total / goal).coerceIn(0.0, 1.0).toFloat()
}
