package ch.kevinjordil.helion.ui.quality

import ch.kevinjordil.helion.R

/**
 * [PersonalBaseline] resolved to a string resource and whether it earns the amber accent.
 * Kept separate from [PersonalBaseline] itself so the pure classification stays free of
 * Android resource ids and is trivial to unit test.
 */
fun personalBaselineMessage(indicator: PersonalBaseline): Pair<Int, Boolean> = when (indicator) {
    PersonalBaseline.InsufficientHistory -> R.string.quality_insufficient_history to false
    is PersonalBaseline.Placed -> when (indicator.position) {
        Position.BELOW -> R.string.quality_below_usual to indicator.isNotable
        Position.USUAL -> R.string.quality_usual to indicator.isNotable
        Position.ABOVE -> R.string.quality_above_usual to indicator.isNotable
    }
}

/**
 * Same mapping as [personalBaselineMessage], but to the short compact_* strings meant for
 * Accueil's tile grid rather than the full sentences used on the detail screen -- see
 * those strings' comment in strings.xml for why a tile needs its own, shorter wording
 * rather than relying on wrapping alone.
 */
fun personalBaselineCompactMessage(indicator: PersonalBaseline): Pair<Int, Boolean> = when (indicator) {
    PersonalBaseline.InsufficientHistory -> R.string.quality_compact_insufficient_history to false
    is PersonalBaseline.Placed -> when (indicator.position) {
        Position.BELOW -> R.string.quality_compact_below_usual to indicator.isNotable
        Position.USUAL -> R.string.quality_compact_usual to indicator.isNotable
        Position.ABOVE -> R.string.quality_compact_above_usual to indicator.isNotable
    }
}

/** [ReferenceIndicator] resolved to a string resource and whether it earns the amber accent, for the given metric. */
fun referenceMessage(metricId: String, indicator: ReferenceIndicator): Pair<Int, Boolean> = when (indicator) {
    ReferenceIndicator.NotApplicable -> R.string.reference_not_applicable to false
    is ReferenceIndicator.Placed -> when (metricId) {
        "steps" -> if (indicator.position == Position.USUAL) {
            R.string.reference_steps_reached to false
        } else {
            R.string.reference_steps_below_goal to indicator.isNotable
        }
        "spo2" -> if (indicator.position == Position.USUAL) {
            R.string.reference_spo2_on_threshold to false
        } else {
            R.string.reference_spo2_below_threshold to indicator.isNotable
        }
        "pai" -> if (indicator.position == Position.USUAL) {
            R.string.reference_pai_reached to false
        } else {
            R.string.reference_pai_below_target to indicator.isNotable
        }
        "sleep_duration" -> when (indicator.position) {
            Position.USUAL -> R.string.reference_sleep_within_range to false
            Position.BELOW -> R.string.reference_sleep_below_range to indicator.isNotable
            Position.ABOVE -> R.string.reference_sleep_above_range to indicator.isNotable
        }
        else -> R.string.reference_not_applicable to false
    }
}
