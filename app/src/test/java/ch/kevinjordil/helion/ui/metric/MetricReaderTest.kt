package ch.kevinjordil.helion.ui.metric

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.store.PointSample
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MetricReaderTest {

    private lateinit var db: HelionDatabase
    private lateinit var reader: MetricReader

    private val heartRate = MetricCatalog.byId("heart_rate")
    private val steps = MetricCatalog.byId("steps")
    private val stress = MetricCatalog.byId("stress")
    private val temperature = MetricCatalog.byId("temperature")

    private fun minute(timestamp: Long, steps: Int? = null, heartRate: Int? = null) =
        MinuteSample(timestamp = timestamp, steps = steps, intensity = null, rawKind = null, heartRate = heartRate, sleepStage = null)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HelionDatabase::class.java,
        ).allowMainThreadQueries().build()
        reader = MetricReader(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `empty range yields an empty state`() = runTest {
        val state = reader.load(heartRate, Range.DAY, now = 100_000)

        assertTrue(state.readings.isEmpty())
        assertNull(state.latest)
        assertNull(state.stats)
    }

    @Test
    fun `a single heart rate sample is its own min, max and average`() = runTest {
        db.minuteSamples().upsertAll(listOf(minute(1_000, heartRate = 64)))

        val state = reader.load(heartRate, Range.DAY, now = 1_000)
        val stats = state.stats
        assertNotNull(stats)
        checkNotNull(stats)

        assertEquals(1, state.readings.size)
        assertEquals(Reading(1_000, 64.0), state.latest)
        assertEquals(64.0, stats.min, 0.0)
        assertEquals(64.0, stats.max, 0.0)
        assertEquals(64.0, stats.average, 0.0)
    }

    @Test
    fun `null-heavy minute rows are skipped, not treated as zero`() = runTest {
        db.minuteSamples().upsertAll(
            listOf(
                minute(1_000, heartRate = 60),
                minute(1_060, heartRate = null),
                minute(1_120, heartRate = 80),
            ),
        )

        val state = reader.load(heartRate, Range.DAY, now = 1_120)

        assertEquals(listOf(60.0, 80.0), state.readings.map { it.value })
        assertEquals(70.0, state.stats!!.average, 0.0)
    }

    @Test
    fun `readings outside the window are excluded`() = runTest {
        db.minuteSamples().upsertAll(listOf(minute(1_000, heartRate = 60), minute(50_000, heartRate = 90)))

        val state = reader.load(heartRate, Range.DAY, now = 1_100)

        assertEquals(listOf(60.0), state.readings.map { it.value })
    }

    @Test
    fun `point series readings map straight through`() = runTest {
        db.pointSamples().upsertAll(
            listOf(PointSample("stress", 1_000, 20.0), PointSample("stress", 2_000, 40.0)),
        )

        val state = reader.load(stress, Range.DAY, now = 2_000)

        assertEquals(listOf(20.0, 40.0), state.readings.map { it.value })
        assertEquals(Reading(2_000, 40.0), state.latest)
        assertEquals(30.0, state.stats!!.average, 0.0)
    }

    @Test
    fun `steps aggregate into a daily total rather than per-minute readings`() = runTest {
        // Same UTC day (dividing by 86_400 keeps both bucketed at day start 0).
        db.minuteSamples().upsertAll(
            listOf(minute(60, steps = 10), minute(120, steps = 5), minute(90_000, steps = 7)),
        )

        val state = reader.load(steps, Range.WEEK, now = 90_000)
        val stats = state.stats
        assertNotNull(stats)
        checkNotNull(stats)

        // Two distinct days: [60, 120] sum to 15, and 90_000 alone sums to 7.
        assertEquals(2, state.readings.size)
        assertEquals(15.0, state.readings[0].value, 0.0)
        assertEquals(7.0, state.readings[1].value, 0.0)
        assertEquals(Reading(86_400, 7.0), state.latest)
        assertEquals(7.0, stats.min, 0.0)
        assertEquals(15.0, stats.max, 0.0)
        assertEquals(11.0, stats.average, 0.0)
    }

    @Test
    fun `a day with no steps at all is simply absent, not a zero bucket`() = runTest {
        db.minuteSamples().upsertAll(listOf(minute(60, steps = 10)))

        val state = reader.load(steps, Range.DAY, now = 60)

        assertEquals(1, state.readings.size)
        assertEquals(10.0, state.readings.single().value, 0.0)
    }

    @Test
    fun `off-body temperature readings are excluded from the chart and the stats`() = runTest {
        db.pointSamples().upsertAll(
            listOf(
                PointSample("temperature", 1_000, 21.9), // strap on a table
                PointSample("temperature", 2_000, 36.5), // on the wrist
                PointSample("temperature", 3_000, 38.4), // still plausible skin temperature
            ),
        )

        val state = reader.load(temperature, Range.DAY, now = 3_000)
        val stats = state.stats
        assertNotNull(stats)
        checkNotNull(stats)

        assertEquals(listOf(36.5, 38.4), state.readings.map { it.value })
        assertEquals(36.5, stats.min, 0.0)
        assertEquals(38.4, stats.max, 0.0)
    }

    @Test
    fun `a range with only off-body temperature readings is reported as no data`() = runTest {
        db.pointSamples().upsertAll(listOf(PointSample("temperature", 1_000, 20.0)))

        val state = reader.load(temperature, Range.DAY, now = 1_000)

        assertTrue(state.readings.isEmpty())
        assertNull(state.latest)
        assertNull(state.stats)
    }

    @Test
    fun `identical values still produce a valid, non-crashing stats range`() = runTest {
        db.pointSamples().upsertAll(
            listOf(PointSample("stress", 1_000, 30.0), PointSample("stress", 2_000, 30.0)),
        )

        val state = reader.load(stress, Range.DAY, now = 2_000)
        val stats = state.stats
        assertNotNull(stats)
        checkNotNull(stats)

        assertEquals(30.0, stats.min, 0.0)
        assertEquals(30.0, stats.max, 0.0)
        assertEquals(30.0, stats.average, 0.0)
    }
}
