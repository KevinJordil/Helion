package ch.kevinjordil.helion.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineSelectionTest {

    private val windowStart = 1_000L
    private val windowEnd = 2_000L // a 1000-second window, easy fractions to reason about

    @Test
    fun `a drag entirely inside the window resolves to the expected instants`() {
        val selection = selectionRange(windowStart, windowEnd, anchorFraction = 0.2f, currentFraction = 0.7f)
        assertEquals(TimelineSelection(1_200, 1_700), selection)
    }

    @Test
    fun `fraction 0 maps to exactly the window start -- the left edge case`() {
        val selection = selectionRange(windowStart, windowEnd, anchorFraction = 0f, currentFraction = 0.5f)
        assertEquals(windowStart, selection?.start)
    }

    @Test
    fun `fraction 1 maps to exactly the window end -- the right edge case`() {
        val selection = selectionRange(windowStart, windowEnd, anchorFraction = 0.5f, currentFraction = 1f)
        assertEquals(windowEnd, selection?.end)
    }

    @Test
    fun `a drag spanning the whole window from edge to edge resolves to the whole window`() {
        val selection = selectionRange(windowStart, windowEnd, anchorFraction = 0f, currentFraction = 1f)
        assertEquals(TimelineSelection(windowStart, windowEnd), selection)
    }

    @Test
    fun `a right-to-left drag resolves to the same range as the equivalent left-to-right drag`() {
        val leftToRight = selectionRange(windowStart, windowEnd, anchorFraction = 0.2f, currentFraction = 0.7f)
        val rightToLeft = selectionRange(windowStart, windowEnd, anchorFraction = 0.7f, currentFraction = 0.2f)
        assertEquals(leftToRight, rightToLeft)
    }

    @Test
    fun `a fraction past either edge is clamped rather than extrapolated`() {
        val selection = selectionRange(windowStart, windowEnd, anchorFraction = -0.5f, currentFraction = 1.5f)
        assertEquals(TimelineSelection(windowStart, windowEnd), selection)
    }

    @Test
    fun `a zero-width drag resolves to a zero-duration selection at that instant, not null`() {
        val selection = selectionRange(windowStart, windowEnd, anchorFraction = 0.4f, currentFraction = 0.4f)
        assertEquals(0L, selection?.durationSeconds)
    }

    @Test
    fun `a degenerate window with no positive span returns null`() {
        assertNull(selectionRange(2_000, 2_000, anchorFraction = 0f, currentFraction = 1f))
        assertNull(selectionRange(2_000, 1_000, anchorFraction = 0f, currentFraction = 1f))
    }
}
