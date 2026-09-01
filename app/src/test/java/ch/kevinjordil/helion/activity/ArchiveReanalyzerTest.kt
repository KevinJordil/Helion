package ch.kevinjordil.helion.activity

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.store.ActivityOrigin
import ch.kevinjordil.helion.store.ActivityStatus
import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.store.SportType
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
 * Covers [ArchiveReanalyzer] end to end against a real (in-memory) database, the same shape
 * [ActivityDetectorTest] already uses -- but over a span wide enough (well past
 * [ArchiveReanalyzer.CHUNK_SECONDS]) that a single call genuinely walks several slices, not
 * just one, so this is also the coverage for the slicing itself: a real session must survive
 * being re-examined slice by slice exactly as it would in one unbounded call.
 */
@RunWith(RobolectricTestRunner::class)
class ArchiveReanalyzerTest {

    private val zone = ZoneOffset.UTC
    private val day = 86_400L

    // Anchor far enough from the epoch that a 30-day baseline lookback and several
    // 14-day reanalysis slices before it all stay comfortably positive.
    private val now = 200 * day + 12 * 3_600L

    private lateinit var db: HelionDatabase
    private lateinit var detector: ActivityDetector
    private lateinit var reanalyzer: ArchiveReanalyzer

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HelionDatabase::class.java,
        ).allowMainThreadQueries().build()
        detector = ActivityDetector(db, zone, now = { now }) { min, max, resting -> "hr $min-$max resting $resting" }
        reanalyzer = ArchiveReanalyzer(db, detector, now = { now })
    }

    @After
    fun tearDown() = db.close()

    private fun quiet(timestamp: Long) =
        MinuteSample(timestamp = timestamp, steps = 0, intensity = 5, rawKind = null, heartRate = 60, sleepStage = null)

    private fun elevated(timestamp: Long, heartRate: Int = 140) =
        MinuteSample(timestamp = timestamp, steps = 20, intensity = 50, rawKind = null, heartRate = heartRate, sleepStage = null)

    /**
     * Uniform 60 bpm every 10 minutes across the full baseline window ending at [now], the
     * same density [ActivityDetectorTest] relies on for a trusted baseline, plus a much
     * longer quiet tail stretching well past [ArchiveReanalyzer.CHUNK_SECONDS] before it so
     * the archive's own earliest sample forces [ArchiveReanalyzer.reanalyze] to walk more
     * than one slice.
     */
    private suspend fun seedQuietArchive(spanDays: Long = 45) {
        val windowStart = now - spanDays * day
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
    fun `a genuine session in an untouched range becomes a candidate`() = runTest {
        seedQuietArchive()
        val start = now - 20 * day
        db.minuteSamples().upsertAll((start..start + 1_440 step 60).map { elevated(it) })

        val outcome = reanalyzer.reanalyze()

        assertTrue(outcome is ReanalysisOutcome.Completed)
        assertEquals(1, (outcome as ReanalysisOutcome.Completed).candidatesCreated)
        val activity = db.activities().all().single()
        assertEquals(ActivityOrigin.DETECTED, activity.origin)
        assertEquals(ActivityStatus.CANDIDATE, activity.status)
        assertEquals(start, activity.startTimestamp)
    }

    @Test
    fun `a session straddling a slice boundary is still found whole`() = runTest {
        seedQuietArchive()
        // Sits right across a CHUNK_SECONDS boundary from the archive's own earliest
        // sample, so the naive (un-overlapped) slicing would split it in two.
        val earliest = now - 45 * day
        val boundary = earliest + ArchiveReanalyzer.CHUNK_SECONDS
        val start = boundary - 720
        db.minuteSamples().upsertAll((start..start + 1_440 step 60).map { elevated(it) })

        val outcome = reanalyzer.reanalyze()

        assertTrue(outcome is ReanalysisOutcome.Completed)
        assertEquals(1, (outcome as ReanalysisOutcome.Completed).candidatesCreated)
        assertEquals(start, db.activities().all().single().startTimestamp)
    }

    @Test
    fun `a confirmed range is left alone, not duplicated`() = settledRangeTest(ActivityStatus.CONFIRMED)

    @Test
    fun `a published range is left alone, not duplicated`() = settledRangeTest(ActivityStatus.PUBLISHED)

    @Test
    fun `a dismissed range is never resurrected`() = settledRangeTest(ActivityStatus.DISMISSED)

    private fun settledRangeTest(status: ActivityStatus) = runTest {
        seedQuietArchive()
        val start = now - 20 * day
        val end = start + 1_500
        val existing = manualActivity(start, end, status)
        val id = db.activities().upsert(existing)

        // Exactly the heart-rate profile that would otherwise read as a clean session.
        db.minuteSamples().upsertAll((start..start + 1_440 step 60).map { elevated(it) })

        val outcome = reanalyzer.reanalyze()

        assertTrue(outcome is ReanalysisOutcome.Completed)
        assertEquals(0, (outcome as ReanalysisOutcome.Completed).candidatesCreated)
        val all = db.activities().all()
        assertEquals(1, all.size)
        assertEquals(existing.copy(id = id), all.single())
    }

    @Test
    fun `a hand-edited manual activity over a flat window is untouched`() = runTest {
        seedQuietArchive()
        val start = now - 20 * day
        val manual = manualActivity(start, start + 3_600)
        val id = db.activities().upsert(manual)

        val outcome = reanalyzer.reanalyze()

        assertTrue(outcome is ReanalysisOutcome.Completed)
        assertEquals(0, (outcome as ReanalysisOutcome.Completed).candidatesCreated)
        val all = db.activities().all()
        assertEquals(1, all.size)
        assertEquals(manual.copy(id = id), all.single())
    }

    @Test
    fun `running it twice produces no duplicates`() = runTest {
        seedQuietArchive()
        val start = now - 20 * day
        db.minuteSamples().upsertAll((start..start + 1_440 step 60).map { elevated(it) })

        val first = reanalyzer.reanalyze()
        assertEquals(1, (first as ReanalysisOutcome.Completed).candidatesCreated)

        val second = reanalyzer.reanalyze()
        assertEquals(0, (second as ReanalysisOutcome.Completed).candidatesCreated)
        assertEquals(1, db.activities().all().size)
    }

    @Test
    fun `records when the last full reanalysis ran`() = runTest {
        seedQuietArchive()
        assertEquals(null, db.syncState().get()?.lastFullDetectionRun)

        reanalyzer.reanalyze()

        assertEquals(now, db.syncState().get()?.lastFullDetectionRun)
    }

    @Test
    fun `an empty archive is reported as nothing stored`() = runTest {
        val outcome = reanalyzer.reanalyze()
        assertEquals(ReanalysisOutcome.NothingStored, outcome)
    }
}
