package ch.kevinjordil.helion.ui.activity

import androidx.compose.ui.graphics.vector.ImageVector
import ch.kevinjordil.helion.store.SportCategory
import ch.kevinjordil.helion.store.SportType
import ch.kevinjordil.helion.ui.theme.HelionIcons

/**
 * The glyph for a sport, chosen by its category so the list stays a small, consistent
 * vocabulary of linear icons (the same style as the rest of the app) rather than a mix of
 * colour emoji. The sport's own name is always shown beside it.
 */
fun sportIcon(sport: SportType?): ImageVector = when (sport?.category) {
    SportCategory.CYCLING -> HelionIcons.Bike
    SportCategory.RUNNING_WALKING -> HelionIcons.Footprints
    SportCategory.WATER -> HelionIcons.Waves
    SportCategory.SNOW_ICE -> HelionIcons.Snowflake
    SportCategory.RACKET -> HelionIcons.Racket
    SportCategory.INDOOR_FITNESS -> HelionIcons.Dumbbell
    SportCategory.TEAM -> HelionIcons.Ball
    SportCategory.OTHER -> HelionIcons.Medal
    null -> HelionIcons.Stopwatch
}
