package ch.kevinjordil.helion.ui.sleep

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.kevinjordil.helion.source.DeviceSleepStage
import ch.kevinjordil.helion.source.SleepStage
import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.store.SleepStageSegment
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the join [SleepReader] makes between minute-derived episodes and the device's own
 * stage segments -- in particular, that a night with segments takes its boundaries from
 * them instead of the minute table's own over-run (see [SleepReader]'s kdoc for why that
 * over-run happens), while a night without segments keeps behaving exactly as
 * [segmentSleepEpisodes] alone would produce.
 */
@RunWith(RobolectricTestRunner::class)
class SleepReaderTest {

    private lateinit var db: HelionDatabase
    private val zurich = ZoneId.of("Europe/Zurich")

    private val anchor: Long = LocalDate.of(2024, 1, 1).atStartOfDay(zurich).toEpochSecond()

    /** 23:00 local, a plausible bedtime inside the night window. */
    private val nightStartMinute = 23L * 60

    private fun ts(minuteOffset: Long): Long = anchor + (nightStartMinute + minuteOffset) * 60

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HelionDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun minute(minuteOffset: Long, stage: Int, heartRate: Int? = 50) = MinuteSample(
        timestamp = ts(minuteOffset),
        steps = null,
        intensity = null,
        rawKind = null,
        heartRate = heartRate,
        sleepStage = stage,
    )

    private fun asleepRun(from: Long, toInclusive: Long, heartRate: Int? = 50): List<MinuteSample> =
        (from..toInclusive).map { minute(it, SleepStage.ASLEEP, heartRate) }

    private fun awakeRun(from: Long, toInclusive: Long, heartRate: Int? = 50): List<MinuteSample> =
        (from..toInclusive).map { minute(it, SleepStage.AWAKE, heartRate) }

    private fun segment(sessionEnd: Long, from: Long, toInclusive: Long, stage: Int) = SleepStageSegment(
        sessionEnd = sessionEnd,
        startTimestamp = ts(from),
        endTimestamp = ts(toInclusive),
        stage = stage,
    )

    @Test
    fun `a night with device stage segments takes its boundaries from them, not the minute table's over-run`() = runTest {
        // The minute table keeps flagging ASLEEP all the way to +469 (06:49), the exact
        // failure mode described for this device: its own SLEEP flag over-runs the true
        // wake time. The device's own hypnogram (the stage segments) ends far earlier, at
        // +415 (05:55), and is what must win.
        val minutes = asleepRun(0, 469) + awakeRun(470, 475)
        db.minuteSamples().upsertAll(minutes)

        // A deliberately wrong, too-late sessionEnd (the row's own TIMESTAMP) -- must NOT
        // be read as a boundary; only the segments' own first start and last end matter.
        val sessionEnd = ts(469) + 3600
        db.sleepStageSegments().upsertAll(
            listOf(
                segment(sessionEnd, 0, 119, DeviceSleepStage.LIGHT),
                segment(sessionEnd, 120, 124, DeviceSleepStage.AWAKE), // a real 5-minute awakening
                segment(sessionEnd, 125, 300, DeviceSleepStage.LIGHT),
                segment(sessionEnd, 301, 415, DeviceSleepStage.DEEP),
            ),
        )
        // A very low heart rate during the confirmed-awake stretch must not leak into the
        // night's minimum -- and a low reading past the segments' own end (in the minute
        // table's over-run tail) must not be reachable at all once the boundary is fixed.
        db.minuteSamples().upsertAll(
            listOf(
                minute(122, SleepStage.ASLEEP, heartRate = 5),
                minute(450, SleepStage.ASLEEP, heartRate = 3),
            ),
        )
        db.minuteSamples().upsertAll(listOf(minute(300, SleepStage.ASLEEP, heartRate = 45)))

        val nights = SleepReader(db, zurich).loadNights(now = ts(475))
        val episode = nights.single()

        assertEquals(ts(0), episode.fellAsleepAt)
        assertEquals(ts(415), episode.wokeAt) // not ts(469), not sessionEnd
        assertEquals(411L, episode.durationAsleepMinutes) // 120 + 176 + 115 = 411 asleep minutes
        assertEquals(411.0 / 416.0, episode.sleepEfficiency, 1e-9)
        assertEquals(1, episode.awakenings)
        assertEquals(5L, episode.awakeningsDurationMinutes)
        assertEquals(45, episode.minHeartRate) // the 5 bpm reading sits inside the awake segment
        assertFalse(episode.isInProgress)
        assertFalse(episode.hasDataGap)
    }

    @Test
    fun `a night with no session at all keeps the minute-derived boundaries`() = runTest {
        val minutes = asleepRun(0, 419) + awakeRun(420, 425)
        db.minuteSamples().upsertAll(minutes)
        // No HUAMI_SLEEP_SESSION_SAMPLE rows for this night.

        val nights = SleepReader(db, zurich).loadNights(now = ts(425))
        val episode = nights.single()

        assertEquals(ts(0), episode.fellAsleepAt)
        assertEquals(ts(419), episode.wokeAt)
        assertEquals(420L, episode.durationAsleepMinutes)
        assertTrue(episode.stageSegments.isEmpty())
    }

    @Test
    fun `a night still in progress keeps its minute-derived boundaries even when a session overlaps it`() = runTest {
        // Still asleep as of "now" -- the archive has not seen a wake-up yet.
        val minutes = asleepRun(0, 300)
        db.minuteSamples().upsertAll(minutes)

        // An early, partial session blob that happens to overlap the still-open night.
        // Its own boundaries must not override the in-progress night's live minute-derived
        // ones -- the night has not ended yet, so the device's own segments cannot be its
        // final word.
        val sessionEnd = ts(260) + 60
        db.sleepStageSegments().upsertAll(listOf(segment(sessionEnd, 0, 250, DeviceSleepStage.LIGHT)))

        val nights = SleepReader(db, zurich).loadNights(now = ts(300))
        val episode = nights.single()

        assertTrue(episode.isInProgress)
        assertEquals(ts(0), episode.fellAsleepAt)
        assertEquals(ts(300), episode.wokeAt)
    }

    @Test
    fun `a data gap stays flagged after the boundary correction`() = runTest {
        // A 10-minute stretch of genuinely missing rows (not a confirmed awakening) in the
        // middle of the night, well past the suspicious-gap threshold, followed by more
        // sleep and a confirmed wake-up. The night's device segments span the same gap and
        // end earlier than the minute table's own tail -- the boundary must move, but the
        // data-gap flag (computed from the minute table, before any boundary correction)
        // must not be lost along with it.
        val minutes = asleepRun(0, 119) + asleepRun(130, 469) + awakeRun(470, 475)
        db.minuteSamples().upsertAll(minutes)

        val sessionEnd = ts(415) + 3600
        db.sleepStageSegments().upsertAll(
            listOf(
                segment(sessionEnd, 0, 119, DeviceSleepStage.LIGHT),
                segment(sessionEnd, 130, 415, DeviceSleepStage.LIGHT),
            ),
        )

        val nights = SleepReader(db, zurich).loadNights(now = ts(475))
        val episode = nights.single()

        assertTrue(episode.hasDataGap)
        assertEquals(ts(415), episode.wokeAt)
    }
}
