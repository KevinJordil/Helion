package ch.kevinjordil.helion.ui.sleep

import ch.kevinjordil.helion.source.DeviceSleepStage
import ch.kevinjordil.helion.source.SleepStage
import ch.kevinjordil.helion.store.MinuteSample
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [resolveSleepPhases]'s own decision, not the estimator's math (see
 * [SleepPhaseTest]) or the session-to-episode matching that fills
 * [SleepEpisode.stageSegments] in the first place (see [SleepReader]): given an episode
 * that already has stage segments, does it prefer them over the estimator, and does it
 * fall back correctly when it has none.
 */
class SleepPhaseSourceTest {

    private val anchor = 1_700_000_000L
    private fun ts(minute: Int): Long = anchor + minute * 60L

    private fun minute(minute: Int, heartRate: Int?, intensity: Int?, stage: Int = SleepStage.ASLEEP) =
        MinuteSample(timestamp = ts(minute), steps = null, intensity = intensity, rawKind = null, heartRate = heartRate, sleepStage = stage)

    private fun episode(minutes: List<MinuteSample>, stageSegments: List<StageSegment> = emptyList()) = SleepEpisode(
        date = LocalDate.of(2024, 1, 1),
        kind = SleepEpisodeKind.NIGHT,
        fellAsleepAt = minutes.first().timestamp,
        wokeAt = minutes.last().timestamp,
        isInProgress = false,
        hasDataGap = false,
        durationAsleepMinutes = minutes.size.toLong(),
        awakenings = 0,
        awakeningsDurationMinutes = 0,
        sleepEfficiency = 1.0,
        minHeartRate = minutes.mapNotNull { it.heartRate }.minOrNull(),
        minutes = minutes,
        stageSegments = stageSegments,
    )

    @Test
    fun `an episode with device stage segments is measured, not estimated`() {
        val minutes = (0 until 60).map { minute(it, heartRate = 50, intensity = 0) }
        val segments = listOf(
            StageSegment(ts(0), ts(29), SleepPhase.DEEP),
            StageSegment(ts(30), ts(59), SleepPhase.LIGHT),
        )

        val source = resolveSleepPhases(episode(minutes, segments))
        check(source is SleepPhaseSource.Measured) { "expected measured, got $source" }
        val phaseAt = source.minutes.associateBy { it.timestamp }
        assertEquals(SleepPhase.DEEP, phaseAt.getValue(ts(15)).phase)
        assertEquals(SleepPhase.LIGHT, phaseAt.getValue(ts(45)).phase)
    }

    @Test
    fun `an episode with no stage segments falls back to the estimator`() {
        val minutes = (0 until 60).map { minute(it, heartRate = 50, intensity = 0) }

        val source = resolveSleepPhases(episode(minutes))
        assertTrue(source is SleepPhaseSource.Estimated)
    }

    @Test
    fun `an episode with no stage segments and no usable heart rate is not estimable either`() {
        val minutes = (0 until 60).map { minute(it, heartRate = null, intensity = 0) }

        val source = resolveSleepPhases(episode(minutes))
        assertEquals(SleepPhaseSource.NotEstimable, source)
    }

    @Test
    fun `a minute the device segments do not cover falls back to its own coarse stage, not a guess`() {
        // Segments only cover the first half of the episode -- a mismatch between the
        // session's own window and the episode's minute-derived one, which should not
        // itself happen often but must degrade safely when it does.
        val minutes = (0 until 40).map { minute(it, heartRate = 50, intensity = 0) } +
            minute(40, heartRate = 50, intensity = 0, stage = SleepStage.AWAKE)
        val segments = listOf(StageSegment(ts(0), ts(29), SleepPhase.DEEP))

        val source = resolveSleepPhases(episode(minutes, segments))
        check(source is SleepPhaseSource.Measured)
        val phaseAt = source.minutes.associateBy { it.timestamp }
        assertEquals(SleepPhase.DEEP, phaseAt.getValue(ts(15)).phase)
        assertEquals(SleepPhase.LIGHT, phaseAt.getValue(ts(35)).phase) // asleep, uncovered -> light, not guessed
        assertEquals(SleepPhase.AWAKE, phaseAt.getValue(ts(40)).phase) // awake, uncovered -> awake
    }

    @Test
    fun `the device stage type codes map to the right displayed phase`() {
        assertEquals(SleepPhase.LIGHT, devicePhaseOf(DeviceSleepStage.LIGHT))
        assertEquals(SleepPhase.DEEP, devicePhaseOf(DeviceSleepStage.DEEP))
        assertEquals(SleepPhase.AWAKE, devicePhaseOf(DeviceSleepStage.AWAKE))
        assertEquals(SleepPhase.REM, devicePhaseOf(DeviceSleepStage.REM))
        assertEquals(null, devicePhaseOf(99))
    }
}
