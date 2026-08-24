package ch.kevinjordil.helion.ui.metric

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.store.PointSample
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
    private val zurich = ZoneId.of("Europe/Zurich")

    private fun minute(timestamp: Long, steps: Int? = null, heartRate: Int? = null) =
        MinuteSample(timestamp = timestamp, steps = steps, intensity = null, rawKind = null, heartRate = heartRate, sleepStage = null)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HelionDatabase::class.java,
        ).allowMainThreadQueries().build()
        reader = MetricReader(db, ZoneId.of("UTC"))
    }

    @After
    fun tearDown() = db.close()

    /** Counts how often work is handed to another thread, to prove [MetricReader] does. */
    private class CountingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var dispatches = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            delegate.dispatch(context, block)
        }
    }

    @Test
    fun `the work is handed to a background dispatcher, not the caller's thread`() = runTest {
        // Both screens call load() from Dispatchers.Main; mapping a year of minute samples
        // there is hundreds of thousands of rows on the thread that draws the frame.
        db.minuteSamples().upsertAll(listOf(minute(1_000, heartRate = 64)))
        val dispatcher = CountingDispatcher(Dispatchers.Default)

        val state = MetricReader(db, ZoneId.of("UTC"), dispatcher).load(heartRate, Range.DAY, now = 1_000)

        assertTrue(dispatcher.dispatches > 0)
        assertEquals(1, state.readings.size)
    }

    @Test
    fun `the oldest daily bucket is a whole day, not a clipped one`() = runTest {
        // now is midday, so `now - one week` lands at midday seven days ago. Without
        // snapping, that day's bucket only counts its afternoon and becomes a spurious Min.
        val utc = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2024, 3, 14, 12, 0, 0, 0, utc).toEpochSecond()
        val sevenDaysAgoMorning = ZonedDateTime.of(2024, 3, 7, 8, 0, 0, 0, utc).toEpochSecond()
        val sevenDaysAgoEvening = ZonedDateTime.of(2024, 3, 7, 20, 0, 0, 0, utc).toEpochSecond()
        db.minuteSamples().upsertAll(
            listOf(
                minute(sevenDaysAgoMorning, steps = 4_000),
                minute(sevenDaysAgoEvening, steps = 2_000),
            ),
        )

        val state = MetricReader(db, utc).load(steps, Range.WEEK, now)

        val oldest = state.readings.first()
        assertEquals(6_000.0, oldest.value, 0.0)
        assertEquals(6_000.0, state.stats!!.min, 0.0)
    }

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
    fun `steps just after local midnight land in today's bucket, not yesterday's UTC day`() = runTest {
        // Zurich is UTC+1 in winter: local midnight on 15 Jan 2024 is 23:00 UTC on the
        // 14th. A fixed 86_400-second UTC bucket would fold both readings below into the
        // *same* day, since both fall before UTC midnight. Local-zone bucketing must not.
        val localMidnight = ZonedDateTime.of(2024, 1, 15, 0, 0, 0, 0, zurich)
        val previousEveningLocal = localMidnight.minusHours(1).toEpochSecond() // 23:00, 14 Jan local
        val justAfterMidnight = localMidnight.plusMinutes(30).toEpochSecond() // 00:30, 15 Jan local

        db.minuteSamples().upsertAll(
            listOf(minute(previousEveningLocal, steps = 3), minute(justAfterMidnight, steps = 5)),
        )

        val zurichReader = MetricReader(db, zurich)
        val state = zurichReader.load(steps, Range.WEEK, now = justAfterMidnight + 3_600)

        assertEquals(2, state.readings.size)
        assertEquals(localMidnight.minusDays(1).toEpochSecond(), state.readings[0].timestamp)
        assertEquals(3.0, state.readings[0].value, 0.0)
        assertEquals(localMidnight.toEpochSecond(), state.readings[1].timestamp)
        assertEquals(5.0, state.readings[1].value, 0.0)
    }

    @Test
    fun `daily bucket boundaries follow the local clock across a DST transition`() = runTest {
        // Zurich springs forward on 31 March 2024: 02:00 CET becomes 03:00 CEST, so that
        // local calendar day is only 23 hours long. A fixed-width bucket would get the
        // gap between consecutive day starts wrong; zone-aware bucketing must not.
        val duringTransitionDay = ZonedDateTime.of(2024, 3, 31, 10, 0, 0, 0, zurich).toEpochSecond()
        val nextDay = ZonedDateTime.of(2024, 4, 1, 10, 0, 0, 0, zurich).toEpochSecond()

        db.minuteSamples().upsertAll(
            listOf(minute(duringTransitionDay, steps = 1), minute(nextDay, steps = 1)),
        )

        val zurichReader = MetricReader(db, zurich)
        val state = zurichReader.load(steps, Range.WEEK, now = nextDay + 3_600)

        assertEquals(2, state.readings.size)
        assertEquals(23 * 3_600L, state.readings[1].timestamp - state.readings[0].timestamp)
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
