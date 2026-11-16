package ch.kevinjordil.helion.ui.activity

import androidx.compose.ui.graphics.Color
import ch.kevinjordil.helion.store.SportCategory
import ch.kevinjordil.helion.store.SportType
import ch.kevinjordil.helion.ui.theme.HelionColors

/**
 * Fixed, permanent order a [SportCategory] resolves its hue by, out of the same eight
 * [HelionColors.metricHues] the metrics use. There are exactly eight categories and exactly
 * eight hues, which is a coincidence worth spending: an activity gets a real colour identity
 * without introducing a second palette to keep in step with the first.
 *
 * Keyed by category rather than by sport: fifty-odd sports cannot each have a
 * distinguishable colour, and the categories are what a glance down the list actually
 * separates -- a racket evening from a ride from a swim.
 *
 * Order is the source of truth and must not be reshuffled: it is what fixes badminton to
 * one colour across releases. Adding a category means appending to it, never inserting.
 */
/**
 * Each sport category's slot in [HelionColors.metricHues], picked so the hue suggests the
 * category where it can (sky for water, turquoise for snow and ice, red for running) while
 * every category keeps a hue of its own.
 */
private val SPORT_CATEGORY_HUE_SLOT = mapOf(
    SportCategory.RUNNING_WALKING to 0, // red
    SportCategory.SNOW_ICE to 1, // turquoise
    SportCategory.CYCLING to 2, // orange
    SportCategory.OTHER to 3, // pink
    SportCategory.RACKET to 4, // lime
    SportCategory.TEAM to 5, // blue
    SportCategory.INDOOR_FITNESS to 6, // violet
    SportCategory.WATER to 7, // sky
)

fun HelionColors.sportColor(category: SportCategory): Color {
    val slot = checkNotNull(SPORT_CATEGORY_HUE_SLOT[category]) { "no colour assigned for sport category: $category" }
    return metricHues[slot]
}

/**
 * The colour for an activity's sport, or null when it has none -- a freely detected
 * candidate carries no sport until the owner sets one (see [ch.kevinjordil.helion.store.Activity.sport]),
 * and inventing a colour for it would be inventing the very guess the app refuses to make.
 * Callers fall back to a neutral.
 */
fun HelionColors.sportColorOrNull(sport: SportType?): Color? = sport?.let { sportColor(it.category) }
