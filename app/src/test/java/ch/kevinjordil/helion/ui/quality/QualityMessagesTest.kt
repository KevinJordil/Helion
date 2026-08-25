package ch.kevinjordil.helion.ui.quality

import ch.kevinjordil.helion.R
import org.junit.Assert.assertEquals
import org.junit.Test

class QualityMessagesTest {

    @Test
    fun `insufficient history never earns amber`() {
        val (resId, isAmber) = personalBaselineMessage(PersonalBaseline.InsufficientHistory)
        assertEquals(R.string.quality_insufficient_history, resId)
        assertEquals(false, isAmber)
    }

    @Test
    fun `each position maps to its own wording and carries the notable flag through`() {
        assertEquals(
            R.string.quality_below_usual to true,
            personalBaselineMessage(PersonalBaseline.Placed(Position.BELOW, isNotable = true)),
        )
        assertEquals(
            R.string.quality_usual to false,
            personalBaselineMessage(PersonalBaseline.Placed(Position.USUAL, isNotable = false)),
        )
        assertEquals(
            R.string.quality_above_usual to true,
            personalBaselineMessage(PersonalBaseline.Placed(Position.ABOVE, isNotable = true)),
        )
    }

    @Test
    fun `no reference states it plainly rather than a blank`() {
        val (resId, isAmber) = referenceMessage("hrv", ReferenceIndicator.NotApplicable)
        assertEquals(R.string.reference_not_applicable, resId)
        assertEquals(false, isAmber)
    }

    @Test
    fun `steps reference wording differs from spo2 for the same position`() {
        val stepsBelow = referenceMessage("steps", ReferenceIndicator.Placed(Position.BELOW, isNotable = false))
        val spo2Below = referenceMessage("spo2", ReferenceIndicator.Placed(Position.BELOW, isNotable = false))
        assertEquals(R.string.reference_steps_below_goal, stepsBelow.first)
        assertEquals(R.string.reference_spo2_below_threshold, spo2Below.first)
        assert(stepsBelow.first != spo2Below.first)
    }

    @Test
    fun `steps and spo2 USUAL wording says the target is met, not just 'usual'`() {
        val stepsUsual = referenceMessage("steps", ReferenceIndicator.Placed(Position.USUAL, isNotable = false))
        val spo2Usual = referenceMessage("spo2", ReferenceIndicator.Placed(Position.USUAL, isNotable = false))
        assertEquals(R.string.reference_steps_reached, stepsUsual.first)
        assertEquals(R.string.reference_spo2_on_threshold, spo2Usual.first)
    }
}
