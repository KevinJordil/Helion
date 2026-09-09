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
private val SPORT_CATEGORY_HUE_ORDER = listOf(
    SportCategory.RACKET,
    SportCategory.CYCLING,
    SportCategory.RUNNING_WALKING,
    SportCategory.WATER,
    SportCategory.SNOW_ICE,
    SportCategory.INDOOR_FITNESS,
    SportCategory.TEAM,
    SportCategory.OTHER,
)

/**
 * This category's permanent colour. Every [SportCategory] has an entry in
 * [SPORT_CATEGORY_HUE_ORDER]; an unmapped one is a bug in that list rather than a state a
 * real category can reach, so it fails loudly instead of falling back to a default hue.
 */
fun HelionColors.sportColor(category: SportCategory): Color {
    val index = SPORT_CATEGORY_HUE_ORDER.indexOf(category)
    check(index >= 0) { "no colour assigned for sport category: $category" }
    return metricHues[index]
}

/**
 * The colour for an activity's sport, or null when it has none -- a freely detected
 * candidate carries no sport until the owner sets one (see [ch.kevinjordil.helion.store.Activity.sport]),
 * and inventing a colour for it would be inventing the very guess the app refuses to make.
 * Callers fall back to a neutral.
 */
fun HelionColors.sportColorOrNull(sport: SportType?): Color? = sport?.let { sportColor(it.category) }
