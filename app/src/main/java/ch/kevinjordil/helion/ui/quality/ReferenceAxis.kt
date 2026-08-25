package ch.kevinjordil.helion.ui.quality

/**
 * Where a value sits against an external, method-independent reference -- a daily goal for
 * `steps`, a widely used resting threshold for `spo2`. Deliberately narrower than
 * [PersonalBaseline]: most of Helion's series have no such reference that can honestly be
 * stated. [referenceIndicatorFor] is the single place that decides which metrics get one,
 * so that list stays in one, auditable spot rather than scattered checks.
 *
 * `hrv` has no reference axis: Gadgetbridge stores a number and Helion does not know
 * whether the strap computes RMSSD, SDNN, or a proprietary index, so a published HRV
 * percentile would be comparing two different quantities while showing a confident digit.
 * `stress` has no reference axis: it is a proprietary Amazfit index with no public
 * definition to compare against. `temperature` has no reference axis: skin temperature
 * depends on measurement site and conditions in a way a single published range cannot
 * capture. `pai` has no reference axis either: PAI is already a normalised score with a
 * commonly cited target elsewhere in the industry, but Helion cannot confirm that this
 * strap's PAI is computed to match that target, and stating one anyway would be exactly
 * the fabricated-precision failure this indicator exists to avoid. `heart_rate` has no
 * reference axis: it is a live instantaneous value here, not a resting measurement, so a
 * resting-heart-rate reference does not apply to it (a resting-HR series exists in the
 * Gadgetbridge export and is not currently ingested -- a legitimate future reference axis,
 * but a new series to ingest, not a rewording of this one).
 */
sealed interface ReferenceIndicator {
    /** No citable, method-independent reference exists for this metric. Said plainly, not left blank. */
    data object NotApplicable : ReferenceIndicator

    /** [isNotable] is the only thing that may render as amber -- see [Position]'s kdoc. */
    data class Placed(val position: Position, val isNotable: Boolean) : ReferenceIndicator
}

/**
 * `steps` against a daily goal the user sets in Réglages (see
 * [ch.kevinjordil.helion.ui.settings.StepsGoal]), not a hardcoded figure presented as
 * medical advice. Meeting or exceeding the goal is [Position.USUAL] ("on target") --
 * exceeding it is not specially called out, since more steps is not automatically the
 * point. Falling short is [Position.BELOW], notable only once the shortfall passes
 * [notableShortfallFraction] of the goal, so a near-miss does not earn amber.
 */
fun referenceForSteps(current: Double, goal: Int, notableShortfallFraction: Double = 0.5): ReferenceIndicator {
    if (goal <= 0) return ReferenceIndicator.NotApplicable
    if (current >= goal) return ReferenceIndicator.Placed(Position.USUAL, isNotable = false)
    val shortfall = (goal - current) / goal
    return ReferenceIndicator.Placed(Position.BELOW, isNotable = shortfall > notableShortfallFraction)
}

/**
 * `spo2` against the widely used >=95% resting threshold. At or above it is
 * [Position.USUAL]; below it is [Position.BELOW], notable once the shortfall passes
 * [notableMarginPoints] percentage points.
 */
fun referenceForSpo2(current: Double, thresholdPercent: Double = 95.0, notableMarginPoints: Double = 3.0): ReferenceIndicator {
    if (current >= thresholdPercent) return ReferenceIndicator.Placed(Position.USUAL, isNotable = false)
    val shortfall = thresholdPercent - current
    return ReferenceIndicator.Placed(Position.BELOW, isNotable = shortfall > notableMarginPoints)
}

/**
 * Single dispatch point: which metrics get a reference axis, and how it is computed for
 * each. Everything not listed here -- `hrv`, `stress`, `temperature`, `pai`, `heart_rate`,
 * and any future metric -- falls through to [ReferenceIndicator.NotApplicable] by default,
 * which is the safe direction: a metric only gets a reference axis by deliberate addition
 * here, never by omission.
 */
fun referenceIndicatorFor(metricId: String, current: Double, stepsGoal: Int): ReferenceIndicator = when (metricId) {
    "steps" -> referenceForSteps(current, stepsGoal)
    "spo2" -> referenceForSpo2(current)
    else -> ReferenceIndicator.NotApplicable
}
