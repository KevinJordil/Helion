package ch.kevinjordil.helion.source

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [Ingestor.notifier]'s own three rules: a candidate is notified at most once ever, several
 * candidates from one pass become one batch call rather than several, and a sink that
 * cannot actually post (the owner's setting is off, or Android's permission is refused --
 * both look identical from here: [CandidateNotificationSink.notifyNewCandidates] returning
 * `false`) never breaks the ingest pass itself or loses the candidate's one remaining
 * chance.
 *
 * [Ingestor.detector] is left null throughout: these tests seed
 * [ActivityStatus.CANDIDATE] rows directly, exactly as a detection pass would have left
 * them, so what is under test is [Ingestor]'s own notification bookkeeping, not detection.
 */
@RunWith(RobolectricTestRunner::class)
class IngestorNotificationTest {

    private lateinit var db: HelionDatabase
    private val dbFileName = "ingestor-notification-test.db"

    /** Records every call, in order, and returns [outcome] for every one of them. */
    private class FakeSink(private val outcome: Boolean) : CandidateNotificationSink {
        val calls = mutableListOf<List<Activity>>()
        override suspend fun notifyNewCandidates(candidates: List<Activity>): Boolean {
            calls += candidates
            return outcome
        }
    }

    private class EmptyReader : ExportReader() {
        override fun read(databasePath: String, since: Watermarks): RawSamples = RawSamples(emptyList(), emptyList())
    }

    private fun candidate(start: Long) = Activity(
        startTimestamp = start,
        endTimestamp = start + 3_600,
        sport = SportType.OTHER,
        title = null,
        notes = null,
        origin = ActivityOrigin.DETECTED,
        status = ActivityStatus.CANDIDATE,
    )

    /** A fresh, file-backed Room connection onto the same on-disk database -- the closest a
     * unit test gets to "the worker process restarted." */
    private fun openDatabase(): HelionDatabase = Room.databaseBuilder(
        ApplicationProvider.getApplicationContext(),
        HelionDatabase::class.java,
        dbFileName,
    ).allowMainThreadQueries().build()

    private fun ingestorWith(database: HelionDatabase, sink: CandidateNotificationSink) =
        Ingestor(EmptyReader(), commands(), FakeExportSignalSuccess(), database) { 1_000 }.apply { notifier = sink }

    private fun commands() = GadgetbridgeCommands(object : CommandSender {
        override fun send(intent: android.content.Intent) = Unit
    })

    private class FakeExportSignalSuccess : ExportSignal {
        override suspend fun awaitExport(timeoutMillis: Long, trigger: () -> Unit): ExportOutcome {
            trigger()
            return ExportOutcome.Success
        }
    }

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
    fun `a candidate already notified is never notified again on a later pass`() = runTest {
        db.activities().upsert(candidate(1_000))
        val sink = FakeSink(outcome = true)
        val ingestor = ingestorWith(db, sink)

        ingestor.ingest("/tmp/export.db")
        ingestor.ingest("/tmp/export.db")

        assertEquals(1, sink.calls.size)
        assertEquals(1, sink.calls.single().size)
        assertTrue(db.activities().unnotifiedCandidates().isEmpty())
    }

    @Test
    fun `a candidate already notified stays that way across a simulated app restart`() = runTest {
        db.activities().upsert(candidate(1_000))
        val firstPassSink = FakeSink(outcome = true)
        ingestorWith(db, firstPassSink).ingest("/tmp/export.db")
        assertEquals(1, firstPassSink.calls.size)

        // Close and reopen the same on-disk database with a brand new Ingestor and a brand
        // new sink -- what a WorkManager restart or a reinstalled worker actually looks
        // like, as opposed to reusing the same in-memory objects.
        db.close()
        db = openDatabase()
        val secondPassSink = FakeSink(outcome = true)
        ingestorWith(db, secondPassSink).ingest("/tmp/export.db")

        assertTrue("a restart must not re-notify an already-notified candidate", secondPassSink.calls.isEmpty())
    }

    @Test
    fun `several candidates from one pass are handed to the sink as a single batch`() = runTest {
        db.activities().upsert(candidate(1_000))
        db.activities().upsert(candidate(2_000))
        db.activities().upsert(candidate(3_000))
        val sink = FakeSink(outcome = true)

        ingestorWith(db, sink).ingest("/tmp/export.db")

        assertEquals("expected exactly one call for all three candidates, not one per candidate", 1, sink.calls.size)
        assertEquals(3, sink.calls.single().size)
        assertTrue(db.activities().unnotifiedCandidates().isEmpty())
    }

    @Test
    fun `a sink that cannot post -- the owner's setting off, or a refused permission -- leaves ingestion and the candidate untouched`() = runTest {
        db.activities().upsert(candidate(1_000))
        val sink = FakeSink(outcome = false)

        val result = ingestorWith(db, sink).ingest("/tmp/export.db")

        assertTrue("ingestion itself must still succeed", result is IngestResult.Ingested)
        assertEquals(1, sink.calls.size)
        // Not marked notified: the candidate keeps its one remaining chance for the next
        // pass, once the setting is turned on or the permission is granted.
        assertEquals(1, db.activities().unnotifiedCandidates().size)
        assertEquals(ActivityStatus.CANDIDATE, db.activities().all().single().status)
    }
}
