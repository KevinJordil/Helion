package ch.kevinjordil.helion.activity

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.store.ActivityOrigin
import ch.kevinjordil.helion.store.ActivityStatus
import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.store.Slot
import ch.kevinjordil.helion.store.SportType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end coverage of [ActivityDetector]: all three passes wired to a real (in-memory)
 * database, in the same shape [ch.kevinjordil.helion.store.ActivityDaoTest] already uses.
 *
 * Every scenario shares one anchor point (`NOW`) and a UTC zone, so distinct-day counting
 * and slot-occurrence resolution stay deterministic regardless of the machine running the
 * test. [seedQuietBaseline] fills the whole 30-day baseline window with uniform resting
 * readings first, in every test: without it [computeHeartRateBaseline] returns null and
 * every pass silently detects nothing, which would make a passing test meaningless.
 */
@RunWith(RobolectricTestRunner::class)
class ActivityDetectorTest {

    private val zone = ZoneOffset.UTC
    private val day = 86_400L

    // An arbitrary, fixed anchor far enough from the epoch that a 30-day lookback and a
    // slot occurrence both stay comfortably positive.
    private val now = 100 * day + 12 * 3_600L

    private lateinit var db: HelionDatabase
    private lateinit var detector: ActivityDetector
    private val notes = mutableListOf<Triple<Int, Int, Int>>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HelionDatabase::class.java,
        ).allowMainThreadQueries().build()
        detector = ActivityDetector(db, zone, now = { now }) { min, max, resting ->
            notes.add(Triple(min, max, resting))
            "hr $min-$max resting $resting"
        }
    }

    @After
    fun tearDown() = db.close()

    private fun quiet(timestamp: Long) =
        MinuteSample(timestamp = timestamp, steps = 0, intensity = 5, rawKind = null, heartRate = 60, sleepStage = null)

    private fun elevated(timestamp: Long, heartRate: Int = 140) =
        MinuteSample(timestamp = timestamp, steps = 20, intensity = 50, rawKind = null, heartRate = heartRate, sleepStage = null)

    /**
     * Uniform 60 bpm every 10 minutes across the full [DetectionThresholds] baseline window
     * ending at [now] -- dense enough that a scenario's own elevated minutes (at most a
     * couple hundred, in these tests) stay a small fraction of the whole window and cannot
     * shift the 85th-percentile spread the way real minute-by-minute history never would.
     */
    private suspend fun seedQuietBaseline() {
        val windowStart = now - DetectionThresholds().baselineWindowDays * day
        val samples = generateSequence(windowStart) { it + 600L }
            .takeWhile { it <= now }
            .map { quiet(it) }
            .toList()
        db.minuteSamples().upsertAll(samples)
    }

    private fun manualActivity(start: Long, end: Long, status: ActivityStatus = ActivityStatus.CONFIRMED) = Activity(
        startTimestamp = start,
        endTimestamp = end,
        sport = SportType.BADMINTON,
        title = "Match du soir",
        notes = "note perso",
        origin = ActivityOrigin.MANUAL,
        status = status,
    )

    @Test
    fun `a day with no activity at all produces nothing -- the most important case`() = runTest {
        seedQuietBaseline()
        val created = detector.detect(now - 7 * day, now)
        assertEquals(0, created)
        assertTrue(db.activities().all().isEmpty())
    }

    @Test
    fun `a slot occurrence is trimmed to the real effort, not the declared boundaries`() = runTest {
        seedQuietBaseline()

        // Day 95 (relative to the epoch), whatever weekday that lands on.
        val matchDate = LocalDate.ofEpochDay(95)
        val dayStart = matchDate.atStartOfDay(zone).toEpochSecond()
        val slotId = db.slots().upsert(
            Slot(
                label = "Badminton du mardi",
                dayOfWeek = matchDate.dayOfWeek,
                startSecondOfDay = 20 * 3_600, // 20:00
                endSecondOfDay = 22 * 3_600, // 22:00
                sport = SportType.BADMINTON,
            ),
        )

        val declaredStart = dayStart + 20 * 3_600
        val declaredEnd = dayStart + 22 * 3_600
        val reachedAt = dayStart + 20 * 3_600 + 10 * 60 // 20:10
        val leftAt = dayStart + 21 * 3_600 + 45 * 60 // 21:45

        val minutes = mutableListOf<MinuteSample>()
        var t = declaredStart
        while (t < reachedAt) { minutes.add(quiet(t)); t += 60 }
        while (t <= leftAt) { minutes.add(elevated(t)); t += 60 }
        while (t < declaredEnd) { minutes.add(quiet(t)); t += 60 }
        db.minuteSamples().upsertAll(minutes)

        val created = detector.detect(dayStart, dayStart + day)

        assertEquals(1, created)
        val activity = db.activities().all().single()
        assertEquals(ActivityOrigin.SLOT, activity.origin)
        assertEquals(slotId, activity.slotId)
        assertEquals(ActivityStatus.CANDIDATE, activity.status)
        assertEquals(reachedAt, activity.startTimestamp)
        assertEquals(leftAt + 60, activity.endTimestamp)
        assertEquals("Badminton du mardi", activity.title)
        assertTrue(activity.detectionContext!!.isNotBlank())
        assertEquals(null, activity.notes)
    }

    @Test
    fun `a hand-edited title on a slot-origin activity survives a later re-detection pass`() = runTest {
        seedQuietBaseline()

        val matchDate = LocalDate.ofEpochDay(95)
        val dayStart = matchDate.atStartOfDay(zone).toEpochSecond()
        db.slots().upsert(
            Slot(
                label = "Badminton du mardi",
                dayOfWeek = matchDate.dayOfWeek,
                startSecondOfDay = 20 * 3_600,
                endSecondOfDay = 22 * 3_600,
                sport = SportType.BADMINTON,
            ),
        )

        val declaredStart = dayStart + 20 * 3_600
        val declaredEnd = dayStart + 22 * 3_600
        val reachedAt = dayStart + 20 * 3_600 + 10 * 60
        val leftAt = dayStart + 21 * 3_600 + 45 * 60

        val minutes = mutableListOf<MinuteSample>()
        var t = declaredStart
        while (t < reachedAt) { minutes.add(quiet(t)); t += 60 }
        while (t <= leftAt) { minutes.add(elevated(t)); t += 60 }
        while (t < declaredEnd) { minutes.add(quiet(t)); t += 60 }
        db.minuteSamples().upsertAll(minutes)

        detector.detect(dayStart, dayStart + day)
        val created = db.activities().all().single()
        db.activities().update(created.copy(title = "Un nom à moi"))

        // Re-running detection over the exact same window must never touch the row again:
        // `overlapping` already reports it as decided, so the hand-edited title stands.
        val createdAgain = detector.detect(dayStart, dayStart + day)

        assertEquals(0, createdAgain)
        assertEquals("Un nom à moi", db.activities().all().single().title)
    }

    @Test
    fun `a flat slot window -- he was not there -- produces nothing`() = runTest {
        seedQuietBaseline()
        val matchDate = LocalDate.ofEpochDay(95)
        val dayStart = matchDate.atStartOfDay(zone).toEpochSecond()
        db.slots().upsert(
            Slot(
                label = "Badminton du mardi",
                dayOfWeek = matchDate.dayOfWeek,
                startSecondOfDay = 20 * 3_600,
                endSecondOfDay = 22 * 3_600,
                sport = SportType.BADMINTON,
            ),
        )
        // No minute samples at all for the declared window -- entirely flat/missing.
        val created = detector.detect(dayStart, dayStart + day)
        assertEquals(0, created)
        assertTrue(db.activities().all().isEmpty())
    }

    @Test
    fun `a free session with a tolerated dip stays ONE activity`() = runTest {
        seedQuietBaseline()
        val start = now - 5 * day
        // A 17-minute dip, matching the longest below-floor gap measured on the real
        // tournament evening -- comfortably inside the 20-minute tolerance.
        val minutes = (start..start + 600 step 60).map { elevated(it) } +
            (start + 1_620..start + 2_220 step 60).map { elevated(it) }

        db.minuteSamples().upsertAll(minutes)
        val created = detector.detect(start - day, start + 2 * day)

        assertEquals(1, created)
        val activity = db.activities().all().single()
        assertEquals(ActivityOrigin.DETECTED, activity.origin)
        assertEquals(SportType.BADMINTON, activity.sport)
        assertEquals(start, activity.startTimestamp)
        assertEquals(start + 2_220 + 60, activity.endTimestamp)
    }

    @Test
    fun `a genuine gap longer than the dip tolerance splits into two activities`() = runTest {
        seedQuietBaseline()
        val start = now - 5 * day
        // A 22-minute gap -- past the 20-minute dip tolerance -- between two 25-minute
        // elevated stretches: two separate outings, not one long one.
        val minutes = (start..start + 1_440 step 60).map { elevated(it) } + // 25 min
            (start + 2_760..start + 4_200 step 60).map { elevated(it) } // 25 min, 22 min later

        db.minuteSamples().upsertAll(minutes)
        val created = detector.detect(start - day, start + 2 * day)

        assertEquals(2, created)
        val activities = db.activities().all().sortedBy { it.startTimestamp }
        assertEquals(start, activities[0].startTimestamp)
        assertEquals(start + 1_500, activities[0].endTimestamp)
        assertEquals(start + 2_760, activities[1].startTimestamp)
        assertEquals(start + 4_260, activities[1].endTimestamp)
    }

    @Test
    fun `a dismissed range is never re-proposed`() = untouchedExistingActivityTest(ActivityStatus.DISMISSED)

    @Test
    fun `a confirmed range is never re-proposed`() = untouchedExistingActivityTest(ActivityStatus.CONFIRMED)

    @Test
    fun `a published range is never re-proposed`() = untouchedExistingActivityTest(ActivityStatus.PUBLISHED)

    private fun untouchedExistingActivityTest(status: ActivityStatus) = runTest {
        seedQuietBaseline()
        val start = now - 5 * day
        val end = start + 1_500
        val existing = manualActivity(start, end, status)
        val id = db.activities().upsert(existing)

        // Exactly the heart-rate profile that would otherwise read as a clean session.
        val minutes = (start..start + 1_440 step 60).map { elevated(it) }
        db.minuteSamples().upsertAll(minutes)

        val created = detector.detect(start - day, start + 2 * day)

        assertEquals(0, created)
        val all = db.activities().all()
        assertEquals(1, all.size)
        assertEquals(existing.copy(id = id), all.single())
    }

    @Test
    fun `a manual activity is never modified, even sitting over a flat window`() = runTest {
        seedQuietBaseline()
        val start = now - 5 * day
        val end = start + 3_600
        val manual = manualActivity(start, end)
        val id = db.activities().upsert(manual)

        // No elevated data anywhere near it -- if detection touched anything here it would
        // only be able to remove or alter this row, never legitimately propose one.
        val created = detector.detect(start - day, start + 2 * day)

        assertEquals(0, created)
        val all = db.activities().all()
        assertEquals(1, all.size)
        assertEquals(manual.copy(id = id), all.single())
    }

    @Test
    fun `too little personal history yields no baseline, so nothing is proposed at all`() = runTest {
        // Only three days of history -- short of minBaselineDays -- despite an otherwise
        // textbook elevated stretch sitting right there.
        val threeDays = (0 until 3).map { quiet(now - it * day) }
        db.minuteSamples().upsertAll(threeDays)
        val start = now - day
        db.minuteSamples().upsertAll((start..start + 1_440 step 60).map { elevated(it) })

        val created = detector.detect(start - day, start + day)
        assertEquals(0, created)
        assertTrue(db.activities().all().isEmpty())
    }
}
