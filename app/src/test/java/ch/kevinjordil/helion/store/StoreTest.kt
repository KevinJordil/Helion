package ch.kevinjordil.helion.store

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StoreTest {

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

    @Test
    fun `re-ingesting the same minute does not duplicate it`() = runTest {
        val dao = db.minuteSamples()
        val sample = MinuteSample(
            timestamp = 1_700_000_000,
            steps = 12,
            intensity = 30,
            rawKind = 1,
            heartRate = 64,
            sleepStage = null,
        )

        dao.upsertAll(listOf(sample))
        dao.upsertAll(listOf(sample))

        val rows = dao.between(1_700_000_000, 1_700_000_000)
        assertEquals(1, rows.size)
        assertEquals(12, rows.single().steps)
    }

    @Test
    fun `re-ingesting a minute with more data overwrites it`() = runTest {
        val dao = db.minuteSamples()
        dao.upsertAll(
            listOf(MinuteSample(1_700_000_060, steps = 5, intensity = null, rawKind = null, heartRate = null, sleepStage = null)),
        )
        dao.upsertAll(
            listOf(MinuteSample(1_700_000_060, steps = 5, intensity = 10, rawKind = 1, heartRate = 70, sleepStage = null)),
        )

        val row = dao.between(1_700_000_060, 1_700_000_060).single()
        assertEquals(70, row.heartRate)
    }

    @Test
    fun `point samples are keyed by series and timestamp`() = runTest {
        val dao = db.pointSamples()
        dao.upsertAll(
            listOf(
                PointSample(series = "stress", timestamp = 1_700_000_000, value = 42.0),
                PointSample(series = "spo2", timestamp = 1_700_000_000, value = 97.0),
                PointSample(series = "stress", timestamp = 1_700_000_000, value = 43.0),
            ),
        )

        assertEquals(43.0, dao.latest("stress")!!.value, 0.0)
        assertEquals(97.0, dao.latest("spo2")!!.value, 0.0)
        assertEquals(1, dao.between("stress", 0, Long.MAX_VALUE).size)
    }

    @Test
    fun `sync state starts empty`() = runTest {
        assertNull(db.syncState().get())
    }
}
