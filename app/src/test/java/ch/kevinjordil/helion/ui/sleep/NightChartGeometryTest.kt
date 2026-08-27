package ch.kevinjordil.helion.ui.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure geometry behind the merged night chart's scrub-chip stage lookup:
 * [phaseSegments] collapsing a per-minute phase track into contiguous runs. This is the
 * one phase rendering that survived the redesign (see `NightChart.kt`'s kdoc) -- the other,
 * full-height background bands drawn from the same segments, was removed outright rather
 * than kept as a second, redundant encoding.
 */
class NightChartGeometryTest {

    private val anchor = 1_700_000_000L
    private fun ts(minute: Int): Long = anchor + minute * 60L

    @Test
    fun `not estimable produces no segments`() {
        assertEquals(emptyList<PhaseSegment>(), phaseSegments(SleepPhaseSource.NotEstimable))
    }

    @Test
    fun `an empty measured track produces no segments`() {
        assertEquals(emptyList<PhaseSegment>(), phaseSegments(SleepPhaseSource.Measured(emptyList())))
    }

    @Test
    fun `a single minute becomes one one-minute segment`() {
        val segments = phaseSegments(SleepPhaseSource.Measured(listOf(PhaseMinute(ts(0), SleepPhase.LIGHT))))
        assertEquals(listOf(PhaseSegment(ts(0), ts(0) + 60, SleepPhase.LIGHT)), segments)
    }

    @Test
    fun `consecutive minutes of the same phase merge into one contiguous run`() {
        val minutes = (0 until 30).map { PhaseMinute(ts(it), SleepPhase.DEEP) }
        val segments = phaseSegments(SleepPhaseSource.Estimated(minutes))
        assertEquals(1, segments.size)
        assertEquals(ts(0), segments.single().startTimestamp)
        assertEquals(ts(29) + 60, segments.single().endTimestamp)
        assertEquals(SleepPhase.DEEP, segments.single().phase)
    }

    @Test
    fun `a phase change starts a new segment even with no time gap`() {
        val minutes = (0 until 10).map { PhaseMinute(ts(it), SleepPhase.LIGHT) } +
            (10 until 20).map { PhaseMinute(ts(it), SleepPhase.DEEP) }
        val segments = phaseSegments(SleepPhaseSource.Measured(minutes))
        assertEquals(2, segments.size)
        assertEquals(SleepPhase.LIGHT, segments[0].phase)
        assertEquals(SleepPhase.DEEP, segments[1].phase)
        assertEquals(ts(10), segments[1].startTimestamp)
    }

    @Test
    fun `a time gap between same-phase minutes starts a new segment instead of bridging it`() {
        val minutes = listOf(PhaseMinute(ts(0), SleepPhase.LIGHT), PhaseMinute(ts(5), SleepPhase.LIGHT))
        val segments = phaseSegments(SleepPhaseSource.Measured(minutes))
        assertEquals(2, segments.size)
        assertTrue(segments.all { it.phase == SleepPhase.LIGHT })
    }
}
