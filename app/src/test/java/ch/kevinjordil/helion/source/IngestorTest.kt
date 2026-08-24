package ch.kevinjordil.helion.source

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.store.SyncState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IngestorTest {

    private lateinit var db: HelionDatabase
    private val sent = mutableListOf<String>()

    private class FakeReader(var samples: RawSamples) : ExportReader() {
        var lastSince: Long = -1
        override fun read(databasePath: String, since: Long): RawSamples {
            lastSince = since
            return RawSamples(
                minutes = samples.minutes.filter { it.timestamp > since },
                points = samples.points.filter { it.timestamp > since },
            )
        }
    }

    /** Ignores [since] entirely -- used to exercise the forward-only watermark guard. */
    private class UnfilteredReader(private val samples: RawSamples) : ExportReader() {
        override fun read(databasePath: String, since: Long): RawSamples = samples
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
    fun `ingests new minutes and advances the watermark`() = runTest {
        val reader = FakeReader(RawSamples(listOf(minute(100), minute(160)), emptyList()))
        val result = ingestor(reader).ingest("/tmp/export.db")

        assertEquals(IngestResult.Ingested(minutes = 2, points = 0), result)
        assertEquals(160, db.syncState().get()!!.lastIngestedTimestamp)
    }

    @Test
    fun `an unchanged export ingests nothing and keeps the watermark`() = runTest {
        val reader = FakeReader(RawSamples(listOf(minute(100)), emptyList()))
        val ing = ingestor(reader)

        ing.ingest("/tmp/export.db")
        val second = ing.ingest("/tmp/export.db")

        assertEquals(IngestResult.Ingested(minutes = 0, points = 0), second)
        assertEquals(100, db.syncState().get()!!.lastIngestedTimestamp)
    }

    @Test
    fun `a failing read leaves the watermark untouched`() = runTest {
        val reader = object : ExportReader() {
            override fun read(databasePath: String, since: Long): RawSamples =
                throw IllegalStateException("truncated export")
        }
        val ing = ingestor(reader)

        val result = ing.ingest("/tmp/export.db")

        assertTrue(result is IngestResult.Failed)
        assertEquals(0, db.syncState().get()!!.lastIngestedTimestamp)
        assertEquals("truncated export", db.syncState().get()!!.lastError)
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
    fun `an export failure signal produces Failed and leaves the watermark untouched`() = runTest {
        db.syncState().put(SyncState(lastIngestedTimestamp = 50, lastSyncAttempt = 0, lastError = null))
        val reader = FakeReader(RawSamples(listOf(minute(100)), emptyList()))
        val signal = FakeExportSignal(ExportOutcome.Failure)

        val result = ingestor(reader, signal).ingest("/tmp/export.db")

        assertTrue(result is IngestResult.Failed)
        assertEquals(50, db.syncState().get()!!.lastIngestedTimestamp)
        // The reader must never be consulted: nothing was actually exported.
        assertEquals(-1, reader.lastSince)
    }

    @Test
    fun `a timeout waiting for the export produces Failed and leaves the watermark untouched`() = runTest {
        db.syncState().put(SyncState(lastIngestedTimestamp = 50, lastSyncAttempt = 0, lastError = null))
        val reader = FakeReader(RawSamples(listOf(minute(100)), emptyList()))
        val signal = FakeExportSignal(ExportOutcome.Timeout, invokeTrigger = false)

        val result = ingestor(reader, signal).ingest("/tmp/export.db")

        assertTrue(result is IngestResult.Failed)
        assertEquals(50, db.syncState().get()!!.lastIngestedTimestamp)
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
    fun `the watermark never moves backwards even if a read reports an older timestamp`() = runTest {
        db.syncState().put(SyncState(lastIngestedTimestamp = 500, lastSyncAttempt = 0, lastError = null))
        // Simulates a reader misbehaving (or a stale export) and reporting something
        // older than what is already recorded; the guard must ignore it regardless.
        val reader = UnfilteredReader(RawSamples(listOf(minute(100)), emptyList()))

        val result = ingestor(reader).ingest("/tmp/export.db")

        assertEquals(IngestResult.Ingested(minutes = 1, points = 0), result)
        assertEquals(500, db.syncState().get()!!.lastIngestedTimestamp)
    }
}
