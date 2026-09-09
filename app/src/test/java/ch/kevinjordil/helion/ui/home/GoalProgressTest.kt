package ch.kevinjordil.helion.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoalProgressTest {

    @Test
    fun `half of the goal is half the bar`() {
        assertEquals(0.5f, goalProgressFraction(4_000.0, 8_000)!!, 0.0001f)
    }

    @Test
    fun `beating the goal fills the bar without overflowing it`() {
        assertEquals(1f, goalProgressFraction(20_000.0, 8_000)!!, 0.0001f)
    }

    @Test
    fun `no reading yet means no bar`() {
        assertNull(goalProgressFraction(null, 8_000))
    }

    @Test
    fun `a goal of zero or less has no fraction to show`() {
        assertNull(goalProgressFraction(4_000.0, 0))
        assertNull(goalProgressFraction(4_000.0, -1))
    }

    @Test
    fun `a day with no steps yet is an empty bar, not a missing one`() {
        assertEquals(0f, goalProgressFraction(0.0, 8_000)!!, 0.0001f)
    }
}
