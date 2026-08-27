package ch.kevinjordil.helion.ui.sleep

import org.junit.Assert.assertEquals
import org.junit.Test

/** Covers [nightBarWidthFraction], the pure geometry behind the history row's bar width (see `SleepHistory.kt`). */
class SleepHistoryGeometryTest {

    @Test
    fun `nightBarWidthFraction is clamped to the unit range and scales linearly against its reference`() {
        assertEquals(0f, nightBarWidthFraction(0), 0.0001f)
        assertEquals(0.5f, nightBarWidthFraction(300, referenceMaxMinutes = 600), 0.0001f)
        assertEquals(1f, nightBarWidthFraction(600, referenceMaxMinutes = 600), 0.0001f)
        assertEquals(1f, nightBarWidthFraction(900, referenceMaxMinutes = 600), 0.0001f)
    }

    @Test
    fun `nightBarWidthFraction does not divide by zero for a non-positive reference`() {
        assertEquals(0f, nightBarWidthFraction(300, referenceMaxMinutes = 0), 0.0001f)
    }
}
