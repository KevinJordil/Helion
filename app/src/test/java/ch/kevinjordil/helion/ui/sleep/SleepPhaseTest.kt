package ch.kevinjordil.helion.ui.sleep

import ch.kevinjordil.helion.source.SleepStage
import ch.kevinjordil.helion.store.MinuteSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers only the estimator's decisions, with hand-built fixtures: a night with an
 * unmistakable deep block and REM block (built from the same baseline so the percentile
 * math is exercised for real, not faked), a heart-rate-free episode returning
 * [SleepPhaseEstimate.NotEstimable], and that smoothing absorbs a single noisy minute
 * instead of letting it flip on its own.
 */
class SleepPhaseTest {

    private val anchor = 1_700_000_000L

    private fun ts(minute: Int): Long = anchor + minute * 60L

    private fun sample(minute: Int, heartRate: Int?, intensity: Int?, stage: Int = SleepStage.ASLEEP) =
        MinuteSample(timestamp = ts(minute), steps = null, intensity = intensity, rawKind = null, heartRate = heartRate, sleepStage = stage)

    @Test
    fun `still low heart rate near the floor is estimated deep, still elevated heart rate is estimated REM, moderate movement is light`() {
        // Three 30-minute blocks sharing one baseline: block 1 is still and near the
        // night's own heart-rate floor (deep candidate), block 2 is equally still but
        // clearly elevated above that floor (REM candidate), block 3 moves enough that
        // neither movement bar is cleared (light, the catch-all).
        val minutes = (0 until 30).map { sample(it, heartRate = 50, intensity = 0) } +
            (30 until 60).map { sample(it, heartRate = 68, intensity = 0) } +
            (60 until 90).map { sample(it, heartRate = 55, intensity = 8) }

        val estimate = estimateSleepPhases(minutes)
        check(estimate is SleepPhaseEstimate.Estimated) { "expected an estimate, got $estimate" }
        val phaseAt = estimate.minutes.associateBy { it.timestamp }

        assertEquals(SleepPhase.DEEP, phaseAt.getValue(ts(15)).phase)
        assertEquals(SleepPhase.REM, phaseAt.getValue(ts(45)).phase)
        assertEquals(SleepPhase.LIGHT, phaseAt.getValue(ts(75)).phase)
    }

    @Test
    fun `an episode with no heart-rate data at all is not estimable`() {
        val minutes = (0 until 60).map { sample(it, heartRate = null, intensity = 0) }
        assertEquals(SleepPhaseEstimate.NotEstimable, estimateSleepPhases(minutes))
    }

    @Test
    fun `an episode too short to establish a baseline is not estimable`() {
        val minutes = (0 until 10).map { sample(it, heartRate = 50, intensity = 0) }
        assertEquals(SleepPhaseEstimate.NotEstimable, estimateSleepPhases(minutes))
    }

    @Test
    fun `smoothing absorbs a single noisy minute instead of letting it flip alone`() {
        // Forty minutes of an unambiguous deep block, except one minute in the middle
        // whose movement alone would read as light. Unsmoothed that minute would flip;
        // majority-vote smoothing over its neighbours should keep it deep.
        val minutes = (0 until 40).map { minute ->
            val intensity = if (minute == 20) 8 else 0
            sample(minute, heartRate = 50, intensity = intensity)
        }

        val estimate = estimateSleepPhases(minutes)
        check(estimate is SleepPhaseEstimate.Estimated) { "expected an estimate, got $estimate" }
        val phaseAt = estimate.minutes.associateBy { it.timestamp }

        assertEquals(SleepPhase.DEEP, phaseAt.getValue(ts(19)).phase)
        assertEquals(SleepPhase.DEEP, phaseAt.getValue(ts(20)).phase)
        assertEquals(SleepPhase.DEEP, phaseAt.getValue(ts(21)).phase)
    }

    @Test
    fun `an awake minute inside an otherwise asleep stretch stays awake through smoothing`() {
        val minutes = (0 until 30).map { sample(it, heartRate = 50, intensity = 0) } +
            sample(30, heartRate = 50, intensity = 0, stage = SleepStage.AWAKE) +
            (31 until 61).map { sample(it, heartRate = 50, intensity = 0) }

        val estimate = estimateSleepPhases(minutes)
        check(estimate is SleepPhaseEstimate.Estimated) { "expected an estimate, got $estimate" }
        val phaseAt = estimate.minutes.associateBy { it.timestamp }

        assertEquals(SleepPhase.AWAKE, phaseAt.getValue(ts(30)).phase)
        assertTrue(phaseAt.getValue(ts(29)).phase != SleepPhase.AWAKE)
    }

    @Test
    fun `awakenings from stage segments count and sum only the AWAKE segments`() {
        val segments = listOf(
            StageSegment(ts(0), ts(29), SleepPhase.LIGHT),
            StageSegment(ts(30), ts(31), SleepPhase.AWAKE),
            StageSegment(ts(32), ts(59), SleepPhase.DEEP),
            StageSegment(ts(60), ts(62), SleepPhase.AWAKE),
            StageSegment(ts(63), ts(90), SleepPhase.REM),
        )

        val result = awakeningsFromStageSegments(segments)

        assertEquals(2, result.count)
        assertEquals(2L + 3L, result.durationMinutes)
    }

    @Test
    fun `awakenings from stage segments is genuinely zero when no segment is AWAKE`() {
        val segments = listOf(
            StageSegment(ts(0), ts(29), SleepPhase.LIGHT),
            StageSegment(ts(30), ts(59), SleepPhase.DEEP),
            StageSegment(ts(60), ts(90), SleepPhase.REM),
        )

        val result = awakeningsFromStageSegments(segments)

        assertEquals(0, result.count)
        assertEquals(0L, result.durationMinutes)
    }

    @Test
    fun `night stage composition is null for an empty track`() {
        assertEquals(null, nightStageComposition(emptyList()))
    }

    @Test
    fun `night stage composition splits minutes into fixed-order shares that sum to one`() {
        val minutes = (0 until 40).map { PhaseMinute(ts(it), SleepPhase.LIGHT) } +
            (40 until 60).map { PhaseMinute(ts(it), SleepPhase.DEEP) } +
            (60 until 100).map { PhaseMinute(ts(it), SleepPhase.REM) }

        val composition = nightStageComposition(minutes)
        checkNotNull(composition)

        // Fixed enum order (AWAKE, LIGHT, REM, DEEP), not sorted by size -- LIGHT is the
        // biggest share here but must still come before REM and DEEP.
        assertEquals(listOf(SleepPhase.LIGHT, SleepPhase.REM, SleepPhase.DEEP), composition.map { it.phase })
        assertEquals(0.4f, composition.first { it.phase == SleepPhase.LIGHT }.fraction, 0.0001f)
        assertEquals(0.4f, composition.first { it.phase == SleepPhase.REM }.fraction, 0.0001f)
        assertEquals(0.2f, composition.first { it.phase == SleepPhase.DEEP }.fraction, 0.0001f)
        assertEquals(1f, composition.sumOf { it.fraction.toDouble() }.toFloat(), 0.0001f)
    }

    @Test
    fun `night stage composition omits a phase entirely absent from the track rather than a zero-width segment`() {
        val minutes = (0 until 10).map { PhaseMinute(ts(it), SleepPhase.LIGHT) }
        val composition = nightStageComposition(minutes)
        checkNotNull(composition)
        assertEquals(listOf(SleepPhase.LIGHT), composition.map { it.phase })
        assertEquals(1f, composition.single().fraction, 0.0001f)
    }

    @Test
    fun `night stage composition of a single minute does not divide by zero`() {
        val composition = nightStageComposition(listOf(PhaseMinute(ts(0), SleepPhase.AWAKE)))
        checkNotNull(composition)
        assertEquals(listOf(SleepPhase.AWAKE), composition.map { it.phase })
        assertEquals(1f, composition.single().fraction, 0.0001f)
    }
}
