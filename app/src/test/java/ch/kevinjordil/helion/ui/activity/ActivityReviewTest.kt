package ch.kevinjordil.helion.ui.activity

import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.store.MinuteSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityReviewTest {

    private fun minute(ts: Long, bpm: Int?) =
        MinuteSample(ts, steps = null, intensity = null, rawKind = null, heartRate = bpm, sleepStage = null)

    @Test
    fun `the publish button says why it is disabled`() {
        assertEquals(R.string.activity_publish_needs_sport, publishBlockerRes(sportKnown = false, serverConfigured = true))
        assertEquals(R.string.activity_publish_needs_server, publishBlockerRes(sportKnown = true, serverConfigured = false))
        assertNull(publishBlockerRes(sportKnown = true, serverConfigured = true))
    }

    @Test
    fun `the chart spans the session, highest reading at the top`() {
        val segments = heartRateChartSegments(
            listOf(minute(0, 100), minute(60, 150), minute(120, 125)),
            start = 0,
            end = 120,
        )

        assertEquals(1, segments.size)
        assertEquals(listOf(ChartPoint(0f, 1f), ChartPoint(0.5f, 0f), ChartPoint(1f, 0.5f)), segments.single())
    }

    @Test
    fun `a long gap without readings splits the line instead of bridging it`() {
        val segments = heartRateChartSegments(
            listOf(minute(0, 100), minute(60, 110), minute(1_200, 120), minute(1_260, 130)),
            start = 0,
            end = 1_260,
        )

        assertEquals(2, segments.size)
    }

    @Test
    fun `fewer than two readings draw nothing`() {
        assertTrue(heartRateChartSegments(listOf(minute(0, 100), minute(60, null)), start = 0, end = 60).isEmpty())
    }

    @Test
    fun `a calm session is not stretched to the full height`() {
        val segments = heartRateChartSegments(listOf(minute(0, 61), minute(60, 64)), start = 0, end = 60)

        // 3 bpm of difference spans a tenth of the 30 bpm minimum range, centred.
        val ys = segments.single().map { it.y }
        assertEquals(0.55f, ys[0], 0.001f)
        assertEquals(0.45f, ys[1], 0.001f)
    }

    @Test
    fun `a perfectly flat session is drawn across the middle`() {
        val segments = heartRateChartSegments(listOf(minute(0, 90), minute(60, 90)), start = 0, end = 60)

        assertEquals(listOf(0.5f, 0.5f), segments.single().map { it.y })
    }
}
