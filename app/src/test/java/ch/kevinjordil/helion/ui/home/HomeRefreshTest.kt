package ch.kevinjordil.helion.ui.home

import ch.kevinjordil.helion.source.ExportUnavailableException
import ch.kevinjordil.helion.source.IngestResult
import ch.kevinjordil.helion.ui.settings.SyncOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRefreshTest {

    @Test
    fun `syncs, then reads, in that order, recording every phase`() = runTest {
        val phases = mutableListOf<RefreshPhase>()
        val events = mutableListOf<String>()

        val outcome = performRefresh(
            onPhase = { phases += it },
            requestSync = { events += "requestSync" },
            awaitSyncFinish = { trigger ->
                events += "awaitSyncFinish:before-trigger"
                trigger()
                events += "awaitSyncFinish:after-trigger"
            },
            copyToCache = { events += "copyToCache"; "/cache/export.db" },
            ingest = { path -> events += "ingest:$path"; IngestResult.Ingested(minutes = 3, points = 4) },
        )

        assertEquals(listOf(RefreshPhase.SYNCING, RefreshPhase.READING, RefreshPhase.DONE), phases)
        assertEquals(
            listOf(
                "awaitSyncFinish:before-trigger",
                "requestSync",
                "awaitSyncFinish:after-trigger",
                "copyToCache",
                "ingest:/cache/export.db",
            ),
            events,
        )
        assertEquals(SyncOutcome.Ingested(3, 4), outcome)
    }

    @Test
    fun `a sync-finish timeout still proceeds to read whatever is on disk`() = runTest {
        val phases = mutableListOf<RefreshPhase>()

        val outcome = performRefresh(
            onPhase = { phases += it },
            requestSync = {},
            // Simulates BroadcastSyncSignal.awaitSyncFinish timing out: it still runs
            // trigger() (registration happens before the trigger, same contract as
            // awaitExport) but returns without the broadcast ever arriving.
            awaitSyncFinish = { trigger -> trigger() },
            copyToCache = { "/cache/export.db" },
            ingest = { IngestResult.Ingested(minutes = 1, points = 0, refreshTriggered = false) },
        )

        assertEquals(listOf(RefreshPhase.SYNCING, RefreshPhase.READING, RefreshPhase.DONE), phases)
        assertEquals(SyncOutcome.Ingested(1, 0, refreshTriggered = false), outcome)
    }

    @Test
    fun `an unreadable export surfaces as Unavailable without calling ingest`() = runTest {
        val outcome = performRefresh(
            onPhase = {},
            requestSync = {},
            awaitSyncFinish = { trigger -> trigger() },
            copyToCache = { throw ExportUnavailableException("moved or deleted") },
            ingest = { error("must not be called: there is no path to ingest") },
        )
        assertEquals(SyncOutcome.Unavailable("moved or deleted"), outcome)
    }
}
