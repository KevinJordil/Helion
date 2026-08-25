package ch.kevinjordil.helion.ui.quality

import org.junit.Assert.assertEquals
import org.junit.Test

class ReferenceAxisTest {

    @Test
    fun `steps meeting the goal is USUAL and not notable`() {
        val indicator = referenceForSteps(current = 8000.0, goal = 8000)
        assertEquals(ReferenceIndicator.Placed(Position.USUAL, isNotable = false), indicator)
    }

    @Test
    fun `steps exceeding the goal is USUAL, not a special ABOVE call-out`() {
        val indicator = referenceForSteps(current = 15000.0, goal = 8000)
        assertEquals(ReferenceIndicator.Placed(Position.USUAL, isNotable = false), indicator)
    }

    @Test
    fun `steps just under the goal is BELOW but not notable`() {
        val indicator = referenceForSteps(current = 7500.0, goal = 8000)
        assertEquals(ReferenceIndicator.Placed(Position.BELOW, isNotable = false), indicator)
    }

    @Test
    fun `steps far under the goal is BELOW and notable`() {
        val indicator = referenceForSteps(current = 1000.0, goal = 8000)
        assertEquals(ReferenceIndicator.Placed(Position.BELOW, isNotable = true), indicator)
    }

    @Test
    fun `a non-positive goal has no reference to compare against`() {
        assertEquals(ReferenceIndicator.NotApplicable, referenceForSteps(current = 5000.0, goal = 0))
    }

    @Test
    fun `spo2 at or above 95 percent is USUAL`() {
        assertEquals(ReferenceIndicator.Placed(Position.USUAL, isNotable = false), referenceForSpo2(current = 97.0))
        assertEquals(ReferenceIndicator.Placed(Position.USUAL, isNotable = false), referenceForSpo2(current = 95.0))
    }

    @Test
    fun `spo2 slightly under 95 percent is BELOW but not notable`() {
        val indicator = referenceForSpo2(current = 93.5)
        assertEquals(ReferenceIndicator.Placed(Position.BELOW, isNotable = false), indicator)
    }

    @Test
    fun `spo2 well under 95 percent is BELOW and notable`() {
        val indicator = referenceForSpo2(current = 88.0)
        assertEquals(ReferenceIndicator.Placed(Position.BELOW, isNotable = true), indicator)
    }

    @Test
    fun `steps and spo2 get a reference axis, everything else does not`() {
        assertEquals(ReferenceIndicator.NotApplicable, referenceIndicatorFor("hrv", current = 45.0, stepsGoal = 8000))
        assertEquals(ReferenceIndicator.NotApplicable, referenceIndicatorFor("stress", current = 40.0, stepsGoal = 8000))
        assertEquals(ReferenceIndicator.NotApplicable, referenceIndicatorFor("temperature", current = 36.5, stepsGoal = 8000))
        assertEquals(ReferenceIndicator.NotApplicable, referenceIndicatorFor("pai", current = 80.0, stepsGoal = 8000))
        assertEquals(ReferenceIndicator.NotApplicable, referenceIndicatorFor("heart_rate", current = 65.0, stepsGoal = 8000))
    }

    @Test
    fun `dispatch routes steps and spo2 to their own comparisons`() {
        assertEquals(referenceForSteps(9000.0, 8000), referenceIndicatorFor("steps", current = 9000.0, stepsGoal = 8000))
        assertEquals(referenceForSpo2(97.0), referenceIndicatorFor("spo2", current = 97.0, stepsGoal = 8000))
    }
}
