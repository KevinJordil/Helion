package ch.kevinjordil.helion.ui.sleep

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [sleepNightToNotify]'s own three rules, tested directly against hand-built [SleepEpisode]
 * fixtures rather than through [SleepReader] or any database -- exactly the reasoning
 * [SleepEpisodeTest]'s own kdoc gives for testing segmentation the same way: fast, and
 * immune to anything else in the pipeline.
 */
class SleepNightNotificationTest {

    private fun night(
        wokeAt: Long,
        isInProgress: Boolean = false,
        hasDataGap: Boolean = false,
        durationMinutes: Long = 420,
    ) = SleepEpisode(
        date = LocalDate.of(2026, 8, 24),
        kind = SleepEpisodeKind.NIGHT,
        fellAsleepAt = wokeAt - durationMinutes * 60,
        wokeAt = wokeAt,
        isInProgress = isInProgress,
        hasDataGap = hasDataGap,
        durationAsleepMinutes = durationMinutes,
        awakenings = 0,
        awakeningsDurationMinutes = 0,
        sleepEfficiency = 0.9,
        minHeartRate = 50,
        minutes = emptyList(),
    )

    private val now = 1_000_000L

    @Test
    fun `a freshly completed night with nothing notified yet is picked`() {
        val n = night(wokeAt = now - 3_600)
        val result = sleepNightToNotify(listOf(n), now, isAlreadyNotified = { false })
        assertEquals(n, result)
    }

    @Test
    fun `a night still in progress is never picked, however fresh`() {
        val n = night(wokeAt = now, isInProgress = true)
        val result = sleepNightToNotify(listOf(n), now, isAlreadyNotified = { false })
        assertNull(result)
    }

    @Test
    fun `a night with a data gap is never picked -- nothing honest to say about it`() {
        val n = night(wokeAt = now - 3_600, hasDataGap = true)
        val result = sleepNightToNotify(listOf(n), now, isAlreadyNotified = { false })
        assertNull(result)
    }

    @Test
    fun `a night already notified is never picked again`() {
        val n = night(wokeAt = now - 3_600)
        val result = sleepNightToNotify(listOf(n), now, isAlreadyNotified = { true })
        assertNull(result)
    }

    @Test
    fun `a night that woke up several days ago is stale and never picked`() {
        val n = night(wokeAt = now - 3 * 24 * 60 * 60)
        val result = sleepNightToNotify(listOf(n), now, isAlreadyNotified = { false })
        assertNull(result)
    }

    @Test
    fun `a night exactly at the staleness boundary is still picked, one second past it is not`() {
        val onBoundary = night(wokeAt = now - SLEEP_NOTIFICATION_STALE_AFTER_SECONDS)
        assertEquals(onBoundary, sleepNightToNotify(listOf(onBoundary), now, isAlreadyNotified = { false }))

        val pastBoundary = night(wokeAt = now - SLEEP_NOTIFICATION_STALE_AFTER_SECONDS - 1)
        assertNull(sleepNightToNotify(listOf(pastBoundary), now, isAlreadyNotified = { false }))
    }

    @Test
    fun `a wake time in the future -- clock skew or a bad export -- is never picked`() {
        val n = night(wokeAt = now + 3_600)
        val result = sleepNightToNotify(listOf(n), now, isAlreadyNotified = { false })
        assertNull(result)
    }

    @Test
    fun `several qualifying nights yield only the most recent one`() {
        val older = night(wokeAt = now - 10 * 60 * 60)
        val newer = night(wokeAt = now - 1 * 60 * 60)
        val result = sleepNightToNotify(listOf(older, newer), now, isAlreadyNotified = { false })
        assertEquals(newer, result)
    }

    @Test
    fun `a nap is never picked, only a night`() {
        val nap = night(wokeAt = now - 3_600).copy(kind = SleepEpisodeKind.NAP)
        val result = sleepNightToNotify(listOf(nap), now, isAlreadyNotified = { false })
        assertNull(result)
    }
}
