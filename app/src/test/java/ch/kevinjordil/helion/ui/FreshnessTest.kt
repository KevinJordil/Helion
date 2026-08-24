package ch.kevinjordil.helion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreshnessTest {

    @Test
    fun `null when nothing has ever been stored`() {
        assertNull(minutesSinceLastSample(latestSampleTimestamp = null, nowSeconds = 1_000))
    }

    @Test
    fun `expressed in whole minutes, rounded down`() {
        assertEquals(2, minutesSinceLastSample(latestSampleTimestamp = 880, nowSeconds = 1_000))
    }

    @Test
    fun `zero when the newest sample is from right now`() {
        assertEquals(0, minutesSinceLastSample(latestSampleTimestamp = 1_000, nowSeconds = 1_000))
    }

    @Test
    fun `never negative even if a sample is timestamped in the future`() {
        // PAI rows are stamped slightly ahead of the minute stream on this device.
        assertEquals(0, minutesSinceLastSample(latestSampleTimestamp = 2_000, nowSeconds = 1_000))
    }

    @Test
    fun `a failed sync cannot make the archive look fresher than it is`() {
        // The old indicator read a "last attempt" timestamp written on every pass, failures
        // included, so a permanently failing sync displayed "0 min" over week-old data.
        // Only the age of the newest stored sample is reported now.
        val aWeek = 7 * 24 * 60 * 60L
        assertEquals(7 * 24 * 60, minutesSinceLastSample(latestSampleTimestamp = 1_000, nowSeconds = 1_000 + aWeek))
    }
}
