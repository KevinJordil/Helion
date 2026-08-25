package ch.kevinjordil.helion.source

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.store.PointSample
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IngestorTest {

    private lateinit var db: HelionDatabase
    private val sent = mutableListOf<String>()

    /** Honours the per-series watermarks the way a real export read does. */
    private class FakeReader(var samples: RawSamples) : ExportReader() {
        var lastSince: Watermarks? = null
        override fun read(databasePath: String, since: Watermarks): RawSamples {
            lastSince = since
            return RawSamples(
                minutes = samples.minutes.filter { it.timestamp > since.minutes },
                points = samples.points.filter { it.timestamp > since.point(it.series) },
            )
        }
    }

    /** Ignores the watermarks entirely -- used to exercise idempotent re-storage. */
    private class UnfilteredReader(private val samples: RawSamples) : ExportReader() {
        override fun read(databasePath: String, since: Watermarks): RawSamples = samples
    }

    /** A plain fake: no BroadcastReceiver, no Android machinery, per the ExportSignal contract. */
    private class FakeExportSignal(
        private val outcome: ExportOutcome,
        private val invokeTrigger: Boolean = true,
    ) : ExportSignal {
        var awaitedTimeoutMillis: Long = -1
        override suspend fun awaitExport(timeoutMillis: Long, trigger: () -> Unit): ExportOutcome {
            awaitedTimeoutMillis = timeoutMillis
            if (invokeTrigger) trigger()
            return outcome
        }
    }

    private fun minute(ts: Long) =
        MinuteSample(ts, steps = 1, intensity = 1, rawKind = 1, heartRate = 60, sleepStage = 0)

    private fun commands() = GadgetbridgeCommands(object : CommandSender {
        override fun send(intent: android.content.Intent) {
            sent += intent.action.orEmpty()
        }
    })

    private fun ingestor(
        reader: ExportReader,
        signal: ExportSignal = FakeExportSignal(ExportOutcome.Success),
    ) = Ingestor(reader, commands(), signal, db) { 1_000 }

    private suspend fun storedMinutes(): List<Long> =
        db.minuteSamples().between(0, Long.MAX_VALUE).map { it.timestamp }

    private suspend fun storedPoints(series: String): List<Long> =
        db.pointSamples().between(series, 0, Long.MAX_VALUE).map { it.timestamp }

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
    fun `ingests new minutes and advances the minute watermark`() = runTest {
        val reader = FakeReader(RawSamples(listOf(minute(100), minute(160)), emptyList()))
        val result = ingestor(reader).ingest("/tmp/export.db")

        assertEquals(IngestResult.Ingested(minutes = 2, points = 0), result)
        assertEquals(listOf(100L, 160L), storedMinutes())
    }

    @Test
    fun `the watermark of a pass is the newest sample already stored, per series`() = runTest {
        val reader = FakeReader(
            RawSamples(
                listOf(minute(100), minute(160)),
                listOf(PointSample("hrv", 50, 42.0), PointSample("stress", 900, 20.0)),
            ),
        )
        val ing = ingestor(reader)

        ing.ingest("/tmp/export.db")
        ing.ingest("/tmp/export.db")

        val since = reader.lastSince!!
        assertEquals(160, since.minutes)
        assertEquals(50, since.point("hrv"))
        assertEquals(900, since.point("stress"))
        // A series that has never been stored backfills from the beginning.
        assertEquals(0, since.point("spo2"))
    }

    @Test
    fun `an unchanged export ingests nothing and keeps what is stored`() = runTest {
        val reader = FakeReader(RawSamples(listOf(minute(100)), emptyList()))
        val ing = ingestor(reader)

        ing.ingest("/tmp/export.db")
        val second = ing.ingest("/tmp/export.db")

        assertEquals(IngestResult.Ingested(minutes = 0, points = 0), second)
        assertEquals(listOf(100L), storedMinutes())
    }

    @Test
    fun `a failing read stores nothing and records the error`() = runTest {
        val reader = object : ExportReader() {
            override fun read(databasePath: String, since: Watermarks): RawSamples =
                throw IllegalStateException("truncated export")
        }

        val result = ingestor(reader).ingest("/tmp/export.db")

        assertTrue(result is IngestResult.Failed)
        assertTrue(storedMinutes().isEmpty())
        assertEquals("truncated export", db.syncState().get()!!.lastError)
    }

    @Test
    fun `a successful pass clears a previously recorded error`() = runTest {
        val failing = object : ExportReader() {
            override fun read(databasePath: String, since: Watermarks): RawSamples =
                throw IllegalStateException("truncated export")
        }
        ingestor(failing).ingest("/tmp/export.db")

        ingestor(FakeReader(RawSamples(listOf(minute(100)), emptyList()))).ingest("/tmp/export.db")

        assertNull(db.syncState().get()!!.lastError)
    }

    @Test
    fun `no configured export means no work and no commands`() = runTest {
        val reader = FakeReader(RawSamples(emptyList(), emptyList()))
        val result = ingestor(reader).ingest(null)

        assertEquals(IngestResult.NoSource, result)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `ingestion asks Gadgetbridge to sync then export`() = runTest {
        val reader = FakeReader(RawSamples(listOf(minute(100)), emptyList()))
        ingestor(reader).ingest("/tmp/export.db")

        assertEquals(2, sent.size)
        assertTrue(sent[0].endsWith("ACTIVITY_SYNC"))
        assertTrue(sent[1].endsWith("DATABASE_EXPORT"))
    }

    @Test
    fun `skipSyncRequest omits the sync request but still triggers and awaits the export`() = runTest {
        val reader = FakeReader(RawSamples(listOf(minute(100)), emptyList()))
        val signal = FakeExportSignal(ExportOutcome.Success)

        val result = ingestor(reader, signal).ingest("/tmp/export.db", force = true, skipSyncRequest = true)

        assertEquals(listOf("nodomain.freeyourgadget.gadgetbridge.command.TRIGGER_DATABASE_EXPORT"), sent)
        assertTrue(result is IngestResult.Ingested)
        assertTrue((result as IngestResult.Ingested).refreshTriggered)
    }

    @Test
    fun `without skipSyncRequest a forced ingest still sends the sync request as before`() = runTest {
        val reader = FakeReader(RawSamples(listOf(minute(100)), emptyList()))
        ingestor(reader).ingest("/tmp/export.db", force = true, skipSyncRequest = false)

        assertEquals(2, sent.size)
        assertTrue(sent[0].endsWith("ACTIVITY_SYNC"))
    }

    @Test
    fun `an export failure signal still reads and ingests the existing file, untriggered`() = runTest {
        val reader = FakeReader(RawSamples(listOf(minute(100)), emptyList()))
        val signal = FakeExportSignal(ExportOutcome.Failure)

        val result = ingestor(reader, signal).ingest("/tmp/export.db")

        // Triggering a fresh export is a freshness optimisation, not a precondition for
        // reading: the file already on disk (e.g. Gadgetbridge's own scheduled export) is
        // still read and ingested, but the result must say the refresh itself did not happen.
        assertEquals(IngestResult.Ingested(minutes = 1, points = 0, refreshTriggered = false), result)
        assertEquals(listOf(100L), storedMinutes())
    }

    @Test
    fun `a timeout waiting for the export still reads and ingests the existing file, untriggered`() = runTest {
        val reader = FakeReader(RawSamples(listOf(minute(100)), emptyList()))
        val signal = FakeExportSignal(ExportOutcome.Timeout, invokeTrigger = false)

        val result = ingestor(reader, signal).ingest("/tmp/export.db")

        assertEquals(IngestResult.Ingested(minutes = 1, points = 0, refreshTriggered = false), result)
        assertEquals(listOf(100L), storedMinutes())
        // requestExport() only ever runs inside the trigger lambda passed to
        // awaitExport, so a timeout (which never invokes it here) means only the sync
        // request was sent for this pass.
        assertEquals(listOf("nodomain.freeyourgadget.gadgetbridge.command.ACTIVITY_SYNC"), sent)
    }

    @Test
    fun `a genuinely unreadable export still fails, even though triggering worked`() = runTest {
        val reader = object : ExportReader() {
            override fun read(databasePath: String, since: Watermarks): RawSamples =
                throw IllegalStateException("truncated export")
        }
        val signal = FakeExportSignal(ExportOutcome.Success)

        val result = ingestor(reader, signal).ingest("/tmp/export.db")

        assertTrue(result is IngestResult.Failed)
        assertTrue(storedMinutes().isEmpty())
    }

    @Test
    fun `after repeated trigger failures, further periodic passes skip triggering`() = runTest {
        val reader = FakeReader(RawSamples(listOf(minute(100)), emptyList()))
        val signal = FakeExportSignal(ExportOutcome.Timeout, invokeTrigger = false)
        val ing = ingestor(reader, signal)

        repeat(Ingestor.TRIGGER_FAILURE_THRESHOLD) { ing.ingest("/tmp/export.db") }
        sent.clear()

        val result = ing.ingest("/tmp/export.db")

        assertTrue(sent.isEmpty())
        assertTrue(result is IngestResult.Ingested)
        assertEquals(false, (result as IngestResult.Ingested).refreshTriggered)
    }

    @Test
    fun `after backing off, a periodic pass retries once the retry interval has elapsed`() = runTest {
        val reader = FakeReader(RawSamples(listOf(minute(100)), emptyList()))
        val signal = FakeExportSignal(ExportOutcome.Timeout, invokeTrigger = false)
        var clock = 1_000L
        val ing = Ingestor(reader, commands(), signal, db) { clock }

        repeat(Ingestor.TRIGGER_FAILURE_THRESHOLD) { ing.ingest("/tmp/export.db") }
        sent.clear()
        ing.ingest("/tmp/export.db")
        assertTrue("still backed off before the interval elapses", sent.isEmpty())

        clock += Ingestor.TRIGGER_RETRY_INTERVAL_SECONDS
        ing.ingest("/tmp/export.db")

        assertTrue("retries after the interval elapses", sent.isNotEmpty())
    }

    @Test
    fun `a forced ingest attempts to trigger even while backed off`() = runTest {
        val reader = FakeReader(RawSamples(listOf(minute(100)), emptyList()))
        val signal = FakeExportSignal(ExportOutcome.Timeout, invokeTrigger = false)
        val ing = ingestor(reader, signal)

        repeat(Ingestor.TRIGGER_FAILURE_THRESHOLD) { ing.ingest("/tmp/export.db") }
        sent.clear()

        ing.ingest("/tmp/export.db", force = true)

        assertTrue(sent.isNotEmpty())
    }

    @Test
    fun `the export wait is bounded, never infinite`() = runTest {
        val reader = FakeReader(RawSamples(emptyList(), emptyList()))
        val signal = FakeExportSignal(ExportOutcome.Success)

        ingestor(reader, signal).ingest("/tmp/export.db")

        assertTrue(signal.awaitedTimeoutMillis > 0)
    }

    @Test
    fun `two ingestion passes cannot overlap`() = runTest {
        // The periodic worker and a manual "Sync now" tap can start at the same moment.
        // Overlapping passes would each register a receiver for the same broadcast, each
        // trigger an export, and read a cache file the other one is replacing.
        var inFlight = 0
        var highWaterMark = 0
        val slowSignal = object : ExportSignal {
            override suspend fun awaitExport(timeoutMillis: Long, trigger: () -> Unit): ExportOutcome {
                inFlight++
                highWaterMark = maxOf(highWaterMark, inFlight)
                delay(1_000)
                inFlight--
                trigger()
                return ExportOutcome.Success
            }
        }
        val ing = ingestor(FakeReader(RawSamples(listOf(minute(100)), emptyList())), slowSignal)

        val first = launch { ing.ingest("/tmp/export.db") }
        val second = launch { ing.ingest("/tmp/export.db") }
        first.join()
        second.join()

        assertEquals(1, highWaterMark)
        assertEquals(listOf(100L), storedMinutes())
    }

    @Test
    fun `re-reading an already stored range is idempotent`() = runTest {
        // A stale or misbehaving export handing back rows that are already archived must
        // not duplicate them, and must not disturb anything else: every write is keyed by
        // timestamp, which is what makes deriving the watermarks from the archive safe.
        val reader = UnfilteredReader(
            RawSamples(listOf(minute(100), minute(160)), listOf(PointSample("hrv", 90, 33.0))),
        )
        val ing = ingestor(reader)

        ing.ingest("/tmp/export.db")
        ing.ingest("/tmp/export.db")

        assertEquals(listOf(100L, 160L), storedMinutes())
        assertEquals(listOf(90L), storedPoints("hrv"))
    }
}
