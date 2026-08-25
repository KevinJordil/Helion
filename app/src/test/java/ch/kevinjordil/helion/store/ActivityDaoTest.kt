package ch.kevinjordil.helion.store

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises [ActivityDao.overlapping] -- the query a later detection step relies on to
 * never resurrect a time range that has already been decided on, whatever the verdict was.
 */
@RunWith(RobolectricTestRunner::class)
class ActivityDaoTest {

    private lateinit var db: HelionDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HelionDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun activityAt(start: Long, end: Long, status: ActivityStatus) = Activity(
        startTimestamp = start,
        endTimestamp = end,
        sport = SportType.BADMINTON,
        title = null,
        notes = null,
        origin = ActivityOrigin.MANUAL,
        status = status,
    )

    @Test
    fun `an untouched time range reports no overlap`() = runTest {
        val dao = db.activities()
        dao.upsert(activityAt(1_000, 2_000, ActivityStatus.CONFIRMED))

        // Well clear of [1_000, 2_000].
        assertTrue(dao.overlapping(5_000, 6_000).isEmpty())
    }

    @Test
    fun `a candidate range is reported as already decided`() = runTest {
        assertOverlapReportedForStatus(ActivityStatus.CANDIDATE)
    }

    @Test
    fun `a confirmed range is reported as already decided`() = runTest {
        assertOverlapReportedForStatus(ActivityStatus.CONFIRMED)
    }

    @Test
    fun `a published range is reported as already decided`() = runTest {
        assertOverlapReportedForStatus(ActivityStatus.PUBLISHED)
    }

    @Test
    fun `a dismissed range is reported as already decided, so a false positive never comes back`() = runTest {
        assertOverlapReportedForStatus(ActivityStatus.DISMISSED)
    }

    private suspend fun assertOverlapReportedForStatus(status: ActivityStatus) {
        val dao = db.activities()
        dao.upsert(activityAt(1_000, 2_000, status))

        // Partial overlap on both edges, and a range fully inside the stored one.
        assertEquals(1, dao.overlapping(500, 1_500).size)
        assertEquals(1, dao.overlapping(1_500, 2_500).size)
        assertEquals(1, dao.overlapping(1_200, 1_800).size)
        // Adjacent but not overlapping must not be reported.
        assertTrue(dao.overlapping(2_001, 3_000).isEmpty())
    }

    @Test
    fun `withStatus filters by status`() = runTest {
        val dao = db.activities()
        dao.upsert(activityAt(1_000, 2_000, ActivityStatus.CANDIDATE))
        dao.upsert(activityAt(3_000, 4_000, ActivityStatus.CONFIRMED))
        dao.upsert(activityAt(5_000, 6_000, ActivityStatus.DISMISSED))

        assertEquals(1, dao.withStatus(ActivityStatus.CANDIDATE).size)
        assertEquals(1, dao.withStatus(ActivityStatus.CONFIRMED).size)
        assertEquals(1, dao.withStatus(ActivityStatus.DISMISSED).size)
        assertEquals(0, dao.withStatus(ActivityStatus.PUBLISHED).size)
    }

    @Test
    fun `a slot deletion nulls out its activities' slotId rather than deleting them`() = runTest {
        val slotId = db.slots().upsert(
            Slot(
                label = "Badminton",
                dayOfWeek = java.time.DayOfWeek.TUESDAY,
                startSecondOfDay = 72_000,
                endSecondOfDay = 79_200,
                sport = SportType.BADMINTON,
            ),
        )
        val activityId = db.activities().upsert(
            activityAt(1_000, 2_000, ActivityStatus.CONFIRMED).copy(origin = ActivityOrigin.SLOT, slotId = slotId),
        )

        db.openHelper.writableDatabase.execSQL("DELETE FROM slot WHERE id = $slotId")

        val survivor = db.activities().get(activityId)!!
        assertEquals(null, survivor.slotId)
    }

    @Test
    fun `deleting an activity cascades to its publication record`() = runTest {
        val activityId = db.activities().upsert(activityAt(1_000, 2_000, ActivityStatus.PUBLISHED))
        db.publications().upsert(
            Publication(
                activityId = activityId,
                target = PublicationTarget.STRAVA,
                remoteId = "123",
                state = PublicationState.PUBLISHED,
                lastAttempt = 1_234,
                lastError = null,
            ),
        )

        db.openHelper.writableDatabase.execSQL("DELETE FROM activity WHERE id = $activityId")

        assertTrue(db.publications().forActivity(activityId).isEmpty())
    }

    @Test
    fun `re-recording a publication attempt replaces rather than duplicates`() = runTest {
        val activityId = db.activities().upsert(activityAt(1_000, 2_000, ActivityStatus.PUBLISHED))
        val dao = db.publications()

        dao.upsert(
            Publication(activityId, PublicationTarget.STRAVA, remoteId = null, state = PublicationState.PENDING, lastAttempt = 1, lastError = null),
        )
        dao.upsert(
            Publication(activityId, PublicationTarget.STRAVA, remoteId = "42", state = PublicationState.PUBLISHED, lastAttempt = 2, lastError = null),
        )

        val rows = dao.forActivity(activityId)
        assertEquals(1, rows.size)
        assertEquals("42", rows.single().remoteId)
        assertEquals(PublicationState.PUBLISHED, rows.single().state)
    }
}
