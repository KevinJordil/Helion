package ch.kevinjordil.helion.source

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.store.PointSample
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
    fun `an export failure signal produces Failed and stores nothing`() = runTest {
        val reader = FakeReader(RawSamples(listOf(minute(100)), emptyList()))
        val signal = FakeExportSignal(ExportOutcome.Failure)

        val result = ingestor(reader, signal).ingest("/tmp/export.db")

        assertTrue(result is IngestResult.Failed)
        assertTrue(storedMinutes().isEmpty())
        // The reader must never be consulted: nothing was actually exported.
        assertNull(reader.lastSince)
    }

    @Test
    fun `a timeout waiting for the export produces Failed and stores nothing`() = runTest {
        val reader = FakeReader(RawSamples(listOf(minute(100)), emptyList()))
        val signal = FakeExportSignal(ExportOutcome.Timeout, invokeTrigger = false)

        val result = ingestor(reader, signal).ingest("/tmp/export.db")

        assertTrue(result is IngestResult.Failed)
        assertTrue(storedMinutes().isEmpty())
        // requestExport() only ever runs inside the trigger lambda passed to
        // awaitExport -- it must be impossible for the export to be requested
        // outside of, or ahead of, the wait that is supposed to gate the read.
        // If a future refactor called commands.requestExport() directly before
        // signal.awaitExport(...), this assertion (not just the two-sends test
        // above, which does not distinguish the two call sites) would catch it.
        assertEquals(listOf("nodomain.freeyourgadget.gadgetbridge.command.ACTIVITY_SYNC"), sent)
    }

    @Test
    fun `the export wait is bounded, never infinite`() = runTest {
        val reader = FakeReader(RawSamples(emptyList(), emptyList()))
        val signal = FakeExportSignal(ExportOutcome.Success)

        ingestor(reader, signal).ingest("/tmp/export.db")

        assertTrue(signal.awaitedTimeoutMillis > 0)
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
