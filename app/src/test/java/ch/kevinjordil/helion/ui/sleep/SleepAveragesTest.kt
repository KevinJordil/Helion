package ch.kevinjordil.helion.ui.sleep

import ch.kevinjordil.helion.source.SleepStage
import ch.kevinjordil.helion.store.MinuteSample
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [computeSleepAverages]'s own handling of the two cases the task's spec calls out by
 * name: a night with no measured (or estimated) stages must not silently drag a stage
 * average down, and an incomplete night must not count as a short night. Plus the
 * transparency requirement -- every average must carry its own honest denominator.
 */
class SleepAveragesTest {

    private fun minutesAsleep(count: Int, heartRate: Int, wokeAt: Long): List<MinuteSample> =
        (0 until count).map { i ->
            MinuteSample(
                timestamp = wokeAt - (count - i) * 60L,
                steps = null,
                intensity = 10,
                rawKind = null,
                heartRate = heartRate,
                sleepStage = SleepStage.ASLEEP,
            )
        }

    /** A night with a real device stage track: half deep, half light, so it resolves as [SleepPhaseSource.Measured]. */
    private fun nightWithMeasuredStages(wokeAt: Long, durationMinutes: Long = 400): SleepEpisode {
        val fellAsleepAt = wokeAt - durationMinutes * 60
        val half = fellAsleepAt + (durationMinutes / 2) * 60
        return night(wokeAt, durationMinutes).copy(
            fellAsleepAt = fellAsleepAt,
            minutes = minutesAsleep(durationMinutes.toInt(), heartRate = 55, wokeAt = wokeAt),
            stageSegments = listOf(
                StageSegment(fellAsleepAt, half, SleepPhase.DEEP),
                StageSegment(half + 60, wokeAt, SleepPhase.LIGHT),
            ),
        )
    }

    /** A night with no stage data and heart-rate too sparse to estimate anything either -- [SleepPhaseSource.NotEstimable]. */
    private fun nightWithNoStages(wokeAt: Long, durationMinutes: Long = 400) = night(wokeAt, durationMinutes)

    private fun night(
        wokeAt: Long,
        durationMinutes: Long = 400,
        isInProgress: Boolean = false,
        hasDataGap: Boolean = false,
        awakenings: Int = 2,
        awakeningsDurationMinutes: Long = 10,
        efficiency: Double = 0.9,
    ) = SleepEpisode(
        date = LocalDate.of(2026, 8, 24),
        kind = SleepEpisodeKind.NIGHT,
        fellAsleepAt = wokeAt - durationMinutes * 60,
        wokeAt = wokeAt,
        isInProgress = isInProgress,
        hasDataGap = hasDataGap,
        durationAsleepMinutes = durationMinutes,
        awakenings = awakenings,
        awakeningsDurationMinutes = awakeningsDurationMinutes,
        sleepEfficiency = efficiency,
        minHeartRate = 50,
        minutes = emptyList(),
    )

    @Test
    fun `a night with no resolvable stages is excluded from the stage average, not zero-filled`() {
        val measured = nightWithMeasuredStages(wokeAt = 100_000)
        val noStages = nightWithNoStages(wokeAt = 200_000)

        val averages = computeSleepAverages(listOf(measured, noStages), SleepAverageWindow.LAST_7)

        // Only the measured night contributes: deep = full first half of its duration.
        assertEquals(1, averages.stageNights)
        assertEquals(2, averages.consideredNights)
        assertEquals(200.0, averages.avgDeepMinutes!!, 2.0)
    }

    @Test
    fun `a stage-resolvable night still counts even when its own value in a phase is zero`() {
        // Every minute is DEEP: REM and LIGHT should read exactly 0.0, not null and not
        // dragged by the no-stage night above -- distinguishing "measured as zero" from
        // "no measurement at all" is the whole point.
        val wokeAt = 100_000L
        val allDeep = night(wokeAt).copy(
            minutes = minutesAsleep(400, heartRate = 55, wokeAt = wokeAt),
            stageSegments = listOf(StageSegment(wokeAt - 400 * 60, wokeAt, SleepPhase.DEEP)),
        )

        val averages = computeSleepAverages(listOf(allDeep), SleepAverageWindow.LAST_7)

        assertEquals(1, averages.stageNights)
        assertEquals(0.0, averages.avgRemMinutes!!, 0.01)
    }

    @Test
    fun `an in-progress night is excluded from the duration average entirely, not counted as a short night`() {
        val complete = night(wokeAt = 100_000, durationMinutes = 420)
        val inProgress = night(wokeAt = 200_000, durationMinutes = 30, isInProgress = true)

        val averages = computeSleepAverages(listOf(complete, inProgress), SleepAverageWindow.LAST_7)

        assertEquals(2, averages.totalNights)
        assertEquals(1, averages.consideredNights)
        assertEquals(420.0, averages.avgDurationMinutes!!, 0.01)
    }

    @Test
    fun `a night with a data gap is excluded from every average the same way`() {
        val complete = night(wokeAt = 100_000, durationMinutes = 420)
        val gappy = night(wokeAt = 200_000, durationMinutes = 60, hasDataGap = true)

        val averages = computeSleepAverages(listOf(complete, gappy), SleepAverageWindow.LAST_7)

        assertEquals(2, averages.totalNights)
        assertEquals(1, averages.consideredNights)
        assertEquals(420.0, averages.avgDurationMinutes!!, 0.01)
    }

    @Test
    fun `the window keeps only the most recent nights by count, not by calendar span`() {
        val nights = (1..10).map { i -> night(wokeAt = i * 100_000L, durationMinutes = i * 10L) }

        val averages = computeSleepAverages(nights, SleepAverageWindow.LAST_5)

        assertEquals(5, averages.totalNights)
        // Nights 6..10 (durations 60..100), average = 80.
        assertEquals(80.0, averages.avgDurationMinutes!!, 0.01)
    }

    @Test
    fun `ALL keeps every night regardless of count`() {
        val nights = (1..40).map { i -> night(wokeAt = i * 100_000L) }
        val averages = computeSleepAverages(nights, SleepAverageWindow.ALL)
        assertEquals(40, averages.totalNights)
    }

    @Test
    fun `a window whose nominal size exceeds the archive reports the real, smaller count`() {
        val nights = listOf(night(wokeAt = 100_000), night(wokeAt = 200_000))
        val averages = computeSleepAverages(nights, SleepAverageWindow.LAST_30)
        assertEquals(2, averages.totalNights)
    }

    @Test
    fun `no considered nights yields null averages, not zeros or a crash`() {
        val onlyGappy = night(wokeAt = 100_000, hasDataGap = true)
        val averages = computeSleepAverages(listOf(onlyGappy), SleepAverageWindow.LAST_7)

        assertEquals(0, averages.consideredNights)
        assertNull(averages.avgDurationMinutes)
        assertNull(averages.avgAwakenings)
        assertNull(averages.avgEfficiency)
        assertNull(averages.avgDeepMinutes)
        assertEquals(0, averages.stageNights)
    }

    @Test
    fun `a night with no respiratory reading is excluded from the respiratory average and its own count`() {
        val withReading = night(wokeAt = 100_000)
        val withoutReading = night(wokeAt = 200_000)
        // avgRespiratoryRate is derived from respiratoryRateReadings; leaving that empty on
        // withoutReading is exactly "no reading for this night".

        val averages = computeSleepAverages(listOf(withReading, withoutReading), SleepAverageWindow.LAST_7)

        assertEquals(0, averages.respiratoryNights)
        assertNull(averages.avgRespiratoryRate)
    }
}
