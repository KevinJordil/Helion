package ch.kevinjordil.helion.ui.sleep

import ch.kevinjordil.helion.source.SleepStage
import ch.kevinjordil.helion.store.MinuteSample
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Segmentation is the analysis core of the sleep screen, so it is tested independently of
 * any database or Compose code, with hand-built fixtures rather than the owner's real
 * export -- see the task's own instruction on that.
 */
class SleepEpisodeTest {

    private val zurich = ZoneId.of("Europe/Zurich")
    private val thresholds = SleepThresholds()

    /** One minute, [minute] minutes after a fixed UTC midnight anchor, for readable fixtures. */
    private fun ts(minute: Long): Long = ANCHOR + minute * 60

    private fun minute(minute: Long, stage: Int, heartRate: Int? = null) =
        MinuteSample(
            timestamp = ts(minute),
            steps = null,
            intensity = null,
            rawKind = null,
            heartRate = heartRate,
            sleepStage = stage,
        )

    private fun asleepRun(fromMinute: Long, toMinuteInclusive: Long, heartRate: Int? = null): List<MinuteSample> =
        (fromMinute..toMinuteInclusive).map { minute(it, SleepStage.ASLEEP, heartRate) }

    private fun awakeRun(fromMinute: Long, toMinuteInclusive: Long, heartRate: Int? = null): List<MinuteSample> =
        (fromMinute..toMinuteInclusive).map { minute(it, SleepStage.AWAKE, heartRate) }

    @Test
    fun `contiguous asleep minutes become a single episode`() {
        // 23:00 to 06:00 local, well within the night window and well past the minimum
        // night duration. A few minutes of confirmed AWAKE readings follow the wake-up,
        // as a real finished night would have, so this is not mistaken for one still in
        // progress.
        val wakeMinute = NIGHT_START_MINUTE + 7 * 60
        val minutes = asleepRun(NIGHT_START_MINUTE, wakeMinute) + awakeRun(wakeMinute + 1, wakeMinute + 5)
        val episodes = segmentSleepEpisodes(minutes, zurich, now = ts(wakeMinute + 5), thresholds)

        assertEquals(1, episodes.size)
        val episode = episodes.single()
        assertEquals(SleepEpisodeKind.NIGHT, episode.kind)
        assertEquals(7 * 60 + 1L, episode.durationAsleepMinutes)
        assertEquals(0, episode.awakenings)
        assertFalse(episode.hasDataGap)
        assertFalse(episode.isInProgress)
    }

    @Test
    fun `a brief awakening does not split one night into two`() {
        // Asleep 23:00-01:00, awake for 10 minutes (a trip to the bathroom), asleep again
        // 01:10-06:00. Well under the 20-minute tolerance.
        val firstStretch = asleepRun(NIGHT_START_MINUTE, NIGHT_START_MINUTE + 120)
        val secondStretch = asleepRun(NIGHT_START_MINUTE + 130, NIGHT_START_MINUTE + 7 * 60)
        val minutes = firstStretch + awakeRun(NIGHT_START_MINUTE + 121, NIGHT_START_MINUTE + 129) + secondStretch

        val episodes = segmentSleepEpisodes(minutes, zurich, now = ts(NIGHT_START_MINUTE + 7 * 60), thresholds)

        assertEquals(1, episodes.size)
        val episode = episodes.single()
        assertEquals(1, episode.awakenings)
        assertEquals(9L, episode.awakeningsDurationMinutes)
        assertEquals(ts(NIGHT_START_MINUTE), episode.fellAsleepAt)
        assertEquals(ts(NIGHT_START_MINUTE + 7 * 60), episode.wokeAt)
        assertFalse(episode.hasDataGap)
    }

    @Test
    fun `a long awakening splits one night into two episodes`() {
        // Asleep 23:00-01:00, awake for 90 minutes -- well past the 20-minute tolerance --
        // then asleep again 02:30-06:00.
        val firstStretch = asleepRun(NIGHT_START_MINUTE, NIGHT_START_MINUTE + 120)
        val secondStretch = asleepRun(NIGHT_START_MINUTE + 210, NIGHT_START_MINUTE + 7 * 60)
        val minutes = firstStretch + awakeRun(NIGHT_START_MINUTE + 121, NIGHT_START_MINUTE + 209) + secondStretch

        val episodes = segmentSleepEpisodes(minutes, zurich, now = ts(NIGHT_START_MINUTE + 7 * 60), thresholds)

        assertEquals(2, episodes.size)
        assertEquals(0, episodes[0].awakenings)
        assertEquals(0, episodes[1].awakenings)
    }

    @Test
    fun `a short daytime episode is a nap, not a night`() {
        // 13:00 to 13:45 local: outside the night window and far short of the minimum
        // night duration either way.
        val minutes = asleepRun(NAP_START_MINUTE, NAP_START_MINUTE + 45)
        val episodes = segmentSleepEpisodes(minutes, zurich, now = ts(NAP_START_MINUTE + 45), thresholds)

        assertEquals(1, episodes.size)
        assertEquals(SleepEpisodeKind.NAP, episodes.single().kind)
    }

    @Test
    fun `a long episode starting outside the night window is still a nap`() {
        // Duration alone is not enough: an afternoon episode long enough to clear the
        // night-duration bar must still classify as a nap because of when it starts.
        val minutes = asleepRun(NAP_START_MINUTE, NAP_START_MINUTE + 4 * 60)
        val episodes = segmentSleepEpisodes(minutes, zurich, now = ts(NAP_START_MINUTE + 4 * 60), thresholds)

        assertEquals(SleepEpisodeKind.NAP, episodes.single().kind)
    }

    @Test
    fun `a short episode starting inside the night window is still a nap`() {
        // Started at a plausible bedtime but only lasted 45 minutes: not a night. Confirmed
        // AWAKE readings afterwards establish this episode has actually ended, so the
        // in-progress exception to the duration test does not apply here.
        val wakeMinute = NIGHT_START_MINUTE + 45
        val minutes = asleepRun(NIGHT_START_MINUTE, wakeMinute) + awakeRun(wakeMinute + 1, wakeMinute + 5)
        val episodes = segmentSleepEpisodes(minutes, zurich, now = ts(wakeMinute + 5), thresholds)

        assertEquals(SleepEpisodeKind.NAP, episodes.single().kind)
    }

    @Test
    fun `an episode crossing midnight is attributed to the date it ends on`() {
        // Falls asleep 23:30 on the anchor date (a Monday, chosen below), wakes at 07:00
        // the next day: this is "Tuesday's night", not Monday's.
        val fellAsleepMinute = 23 * 60 + 30
        val wokeMinute = fellAsleepMinute + 7 * 60 + 30
        val minutes = asleepRun(fellAsleepMinute.toLong(), wokeMinute.toLong())
        val episodes = segmentSleepEpisodes(minutes, zurich, now = ts(wokeMinute.toLong()), thresholds)

        val episode = episodes.single()
        assertEquals(ANCHOR_DATE.plusDays(1), episode.date)
    }

    @Test
    fun `an episode still in progress is not presented as a finished duration`() {
        // Asleep from 23:00, still asleep at the last recorded minute (01:30): the night
        // has not been observed to end.
        val minutes = asleepRun(NIGHT_START_MINUTE, NIGHT_START_MINUTE + 150)
        val episodes = segmentSleepEpisodes(minutes, zurich, now = ts(NIGHT_START_MINUTE + 150), thresholds)

        val episode = episodes.single()
        assertTrue(episode.isInProgress)
        // Still classified as a night on the strength of the start time alone: the
        // duration test cannot have been passed yet, and must not demote it to a nap.
        assertEquals(SleepEpisodeKind.NIGHT, episode.kind)
    }

    @Test
    fun `an episode that has a confirmed later awake reading is not in progress`() {
        val asleepPart = asleepRun(NIGHT_START_MINUTE, NIGHT_START_MINUTE + 7 * 60)
        val minutes = asleepPart + awakeRun(NIGHT_START_MINUTE + 7 * 60 + 1, NIGHT_START_MINUTE + 7 * 60 + 5)
        val episodes = segmentSleepEpisodes(minutes, zurich, now = ts(NIGHT_START_MINUTE + 7 * 60 + 5), thresholds)

        assertFalse(episodes.single().isInProgress)
    }

    @Test
    fun `a night with a data gap is flagged rather than silently shortened`() {
        // Asleep 23:00-01:00, then ten minutes of genuinely missing rows (not a recorded
        // awake reading), then asleep again 01:15-06:00. The gap is short enough to merge
        // (under the 20-minute tolerance) but long enough (over the 5-minute suspicious
        // threshold) to flag.
        val firstStretch = asleepRun(NIGHT_START_MINUTE, NIGHT_START_MINUTE + 120)
        val secondStretch = asleepRun(NIGHT_START_MINUTE + 135, NIGHT_START_MINUTE + 7 * 60)
        val minutes = firstStretch + secondStretch // nothing recorded in between at all

        val episodes = segmentSleepEpisodes(minutes, zurich, now = ts(NIGHT_START_MINUTE + 7 * 60), thresholds)

        val episode = episodes.single()
        assertTrue(episode.hasDataGap)
    }

    @Test
    fun `a two-minute gap is ordinary jitter, not a suspicious data gap`() {
        // Real exports show occasional single-minute sync hiccups during otherwise
        // continuous wear; that must not be flagged as an incomplete night.
        val firstStretch = asleepRun(NIGHT_START_MINUTE, NIGHT_START_MINUTE + 120)
        val secondStretch = asleepRun(NIGHT_START_MINUTE + 123, NIGHT_START_MINUTE + 7 * 60)
        val minutes = firstStretch + secondStretch

        val episodes = segmentSleepEpisodes(minutes, zurich, now = ts(NIGHT_START_MINUTE + 7 * 60), thresholds)

        assertFalse(episodes.single().hasDataGap)
    }

    @Test
    fun `no sleep at all yields no episodes`() {
        val minutes = awakeRun(NIGHT_START_MINUTE, NIGHT_START_MINUTE + 7 * 60)
        val episodes = segmentSleepEpisodes(minutes, zurich, now = ts(NIGHT_START_MINUTE + 7 * 60), thresholds)
        assertTrue(episodes.isEmpty())
    }

    @Test
    fun `an empty minute list yields no episodes`() {
        assertTrue(segmentSleepEpisodes(emptyList(), zurich, now = 0L, thresholds).isEmpty())
    }

    @Test
    fun `minimum heart rate is taken only from asleep minutes`() {
        val asleepPart = asleepRun(NIGHT_START_MINUTE, NIGHT_START_MINUTE + 60, heartRate = 55)
        // A lower reading recorded while awake, just before falling asleep, must not win.
        val minutes = awakeRun(NIGHT_START_MINUTE - 5, NIGHT_START_MINUTE - 1, heartRate = 40) + asleepPart
        val episodes = segmentSleepEpisodes(minutes, zurich, now = ts(NIGHT_START_MINUTE + 60), thresholds)
        assertEquals(55, episodes.single().minHeartRate)
    }

    @Test
    fun `sleep efficiency accounts for time spent briefly awake inside the episode`() {
        val firstStretch = asleepRun(NIGHT_START_MINUTE, NIGHT_START_MINUTE + 59) // 60 minutes asleep
        val secondStretch = asleepRun(NIGHT_START_MINUTE + 70, NIGHT_START_MINUTE + 129) // another 60 minutes
        val minutes = firstStretch + awakeRun(NIGHT_START_MINUTE + 60, NIGHT_START_MINUTE + 69) + secondStretch

        val episodes = segmentSleepEpisodes(minutes, zurich, now = ts(NIGHT_START_MINUTE + 129), thresholds)
        val episode = episodes.single()

        // 120 minutes asleep out of a 130-minute span (23:00 to 01:10 inclusive).
        assertEquals(120L, episode.durationAsleepMinutes)
        assertEquals(120.0 / 130.0, episode.sleepEfficiency, 1e-9)
    }

    companion object {
        // A fixed Monday 00:00 UTC anchor in Europe/Zurich (UTC+1 in this fixture,
        // no DST ambiguity to worry about): 2024-01-01 00:00 UTC is a Monday.
        private val ANCHOR_DATE: LocalDate = LocalDate.of(2024, 1, 1)
        private val ANCHOR: Long = ANCHOR_DATE.atStartOfDay(ZoneId.of("Europe/Zurich")).toEpochSecond()

        /** 23:00 local, a plausible bedtime inside the night window. */
        private const val NIGHT_START_MINUTE = 23L * 60

        /** 13:00 local, outside the night window. */
        private const val NAP_START_MINUTE = 13L * 60
    }
}
