package ch.kevinjordil.helion.notification

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.store.ActivityOrigin
import ch.kevinjordil.helion.store.ActivityStatus
import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.store.SportType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CandidateActionsTest {

    private lateinit var db: HelionDatabase
    private val cancelled = mutableListOf<Long>()
    private val publishing = mutableListOf<Long>()
    private val enqueued = mutableListOf<Long>()

    private val actions by lazy {
        CandidateActions(
            activities = db.activities(),
            cancelNotification = { cancelled += it },
            showPublishing = { publishing += it.id },
            enqueuePublish = { enqueued += it },
        )
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HelionDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed(status: ActivityStatus = ActivityStatus.CANDIDATE): Long = db.activities().upsert(
        Activity(
            startTimestamp = 1_000,
            endTimestamp = 4_600,
            sport = SportType.BADMINTON,
            title = null,
            notes = null,
            origin = ActivityOrigin.SLOT,
            status = status,
            provisional = true,
        ),
    )

    @Test
    fun `confirm settles the candidate and removes its notification`() = runTest {
        val id = seed()

        actions.confirm(id)

        val stored = db.activities().get(id)!!
        assertEquals(ActivityStatus.CONFIRMED, stored.status)
        assertFalse(stored.provisional)
        assertEquals(listOf(id), cancelled)
    }

    @Test
    fun `dismiss marks it dismissed so it is never proposed again`() = runTest {
        val id = seed()

        actions.dismiss(id)

        assertEquals(ActivityStatus.DISMISSED, db.activities().get(id)!!.status)
        assertEquals(listOf(id), cancelled)
    }

    @Test
    fun `an old notification button never overrides a decision made in the app since`() = runTest {
        val id = seed(status = ActivityStatus.DISMISSED)

        actions.confirm(id)

        assertEquals(ActivityStatus.DISMISSED, db.activities().get(id)!!.status)
        assertEquals(listOf(id), cancelled)
    }

    @Test
    fun `publish confirms first, shows sending and hands the send to the worker`() = runTest {
        val id = seed()

        actions.publish(id)

        assertEquals(ActivityStatus.CONFIRMED, db.activities().get(id)!!.status)
        assertEquals(listOf(id), publishing)
        assertEquals(listOf(id), enqueued)
        assertTrue(cancelled.isEmpty())
    }

    @Test
    fun `a dismissed activity is not published from an old notification`() = runTest {
        val id = seed(status = ActivityStatus.DISMISSED)

        actions.publish(id)

        assertTrue(enqueued.isEmpty())
        assertEquals(listOf(id), cancelled)
    }
}
