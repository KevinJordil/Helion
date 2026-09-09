package ch.kevinjordil.helion.ui.activity

import ch.kevinjordil.helion.store.SportCategory
import ch.kevinjordil.helion.store.SportType

/**
 * A sport's glyph, as an emoji rather than a vector icon.
 *
 * The app ships `material-icons-core`, which has no sport pictograms at all, and pulling in
 * the extended icon set to draw a shuttlecock would add a large dependency for decoration.
 * Emoji are already on the device, are drawn in colour, cost nothing, and read instantly at
 * the size a list row needs -- which is exactly the job here.
 *
 * Most sports resolve through their [SportCategory] (see [categoryEmoji]); the handful the
 * owner actually records get their own glyph, because "badminton" deserves a shuttlecock
 * rather than the generic racket the whole category would otherwise share.
 */
private val SPORT_EMOJI: Map<SportType, String> = mapOf(
    SportType.BADMINTON to "🏸",
    SportType.TENNIS to "🎾",
    SportType.TABLE_TENNIS to "🏓",
    SportType.RUN to "🏃",
    SportType.TRAIL_RUN to "⛰️",
    SportType.WALK to "🚶",
    SportType.HIKE to "🥾",
    SportType.RIDE to "🚴",
    SportType.MOUNTAIN_BIKE_RIDE to "🚵",
    SportType.SWIM to "🏊",
    SportType.ROCK_CLIMBING to "🧗",
    SportType.WEIGHT_TRAINING to "🏋️",
    SportType.YOGA to "🧘",
    SportType.ALPINE_SKI to "⛷️",
    SportType.SNOWBOARD to "🏂",
    SportType.ICE_SKATE to "⛸️",
    SportType.SOCCER to "⚽",
    SportType.BASKETBALL to "🏀",
    SportType.VOLLEYBALL to "🏐",
    SportType.ROWING to "🚣",
    SportType.KAYAKING to "🛶",
    SportType.SURFING to "🏄",
    SportType.GOLF to "⛳",
    SportType.SKATEBOARD to "🛹",
    SportType.WORKOUT to "💪",
)

/** The fallback glyph for every sport in a category that has no entry of its own. */
private fun categoryEmoji(category: SportCategory): String = when (category) {
    SportCategory.CYCLING -> "🚴"
    SportCategory.RUNNING_WALKING -> "🏃"
    SportCategory.WATER -> "🏊"
    SportCategory.SNOW_ICE -> "⛷️"
    SportCategory.RACKET -> "🏸"
    SportCategory.INDOOR_FITNESS -> "🏋️"
    SportCategory.TEAM -> "⚽"
    SportCategory.OTHER -> "🏅"
}

/**
 * The glyph for [sport], or a neutral marker when an activity has no sport yet -- a freely
 * detected candidate carries none until the owner sets one (see
 * [ch.kevinjordil.helion.store.Activity.sport]), and picking a sport's icon for it would be
 * making exactly the guess the app refuses to make.
 */
fun sportEmoji(sport: SportType?): String =
    sport?.let { SPORT_EMOJI[it] ?: categoryEmoji(it.category) } ?: "•"
