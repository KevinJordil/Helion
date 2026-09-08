package ch.kevinjordil.helion.source

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.store.MinuteSample
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [Ingestor.sleepNotifier]'s own trigger rule: only a pass that actually stored new minute
 * samples or new stage segments asks the hook at all -- see [Ingestor.runSleepNotificationOver]'s
 * own kdoc for why nothing new can complete a night that was not already complete on the
 * previous pass. The dedup/freshness/in-progress decisions themselves belong to
 * [ch.kevinjordil.helion.ui.sleep.sleepNightToNotify] and are covered by
 * [ch.kevinjordil.helion.ui.sleep.SleepNightNotificationTest] instead -- this file is only
 * about [Ingestor] asking the hook at the right moments and never letting it break
 * ingestion.
 */
@RunWith(RobolectricTestRunner::class)
class IngestorSleepNotificationTest {

    private lateinit var db: HelionDatabase
    private val dbFileName = "ingestor-sleep-notification-test.db"

    private class RecordingSink(private val throwOnCheck: Boolean = false) : SleepNightNotificationSink {
        var calls = 0
        override suspend fun checkForCompletedNight() {
            calls += 1
            if (throwOnCheck) error("boom")
        }
    }

    private class FakeReaderWithMinutes : ExportReader() {
        override fun read(databasePath: String, since: Watermarks): RawSamples = RawSamples(
            minutes = listOf(
                MinuteSample(timestamp = 1_000, steps = 0, intensity = 0, rawKind = null, heartRate = 60, sleepStage = 1),
            ),
            points = emptyList(),
        )
    }

    private class EmptyReader : ExportReader() {
        override fun read(databasePath: String, since: Watermarks): RawSamples = RawSamples(emptyList(), emptyList())
    }

    private fun openDatabase(): HelionDatabase = Room.databaseBuilder(
        ApplicationProvider.getApplicationContext(),
        HelionDatabase::class.java,
        dbFileName,
    ).allowMainThreadQueries().build()

    private fun commands() = GadgetbridgeCommands(object : CommandSender {
        override fun send(intent: android.content.Intent) = Unit
    })

    private class FakeExportSignalSuccess : ExportSignal {
        override suspend fun awaitExport(timeoutMillis: Long, trigger: () -> Unit): ExportOutcome {
            trigger()
            return ExportOutcome.Success
        }
    }

    private fun ingestorWith(reader: ExportReader, sink: SleepNightNotificationSink) =
        Ingestor(reader, commands(), FakeExportSignalSuccess(), db) { 1_000 }.apply { sleepNotifier = sink }

    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(dbFileName)
        db = openDatabase()
    }

    @After
    fun tearDown() {
        db.close()
        ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(dbFileName)
    }

    @Test
    fun `a pass that stores new minute samples asks the sleep notifier once`() = runTest {
        val sink = RecordingSink()
        ingestorWith(FakeReaderWithMinutes(), sink).ingest("/tmp/export.db")

        assertEquals(1, sink.calls)
    }

    @Test
    fun `a pass with nothing new never asks the sleep notifier`() = runTest {
        val sink = RecordingSink()
        ingestorWith(EmptyReader(), sink).ingest("/tmp/export.db")

        assertEquals(0, sink.calls)
    }

    @Test
    fun `a sleep notifier that throws never turns a successful pass into a failure`() = runTest {
        val sink = RecordingSink(throwOnCheck = true)
        val result = ingestorWith(FakeReaderWithMinutes(), sink).ingest("/tmp/export.db")

        assertEquals(1, sink.calls)
        assertTrue("ingestion itself must still succeed", result is IngestResult.Ingested)
    }

    @Test
    fun `no sleep notifier wired means the check simply never runs`() = runTest {
        val ingestor = Ingestor(FakeReaderWithMinutes(), commands(), FakeExportSignalSuccess(), db) { 1_000 }
        val result = ingestor.ingest("/tmp/export.db")

        assertTrue(result is IngestResult.Ingested)
    }
}
