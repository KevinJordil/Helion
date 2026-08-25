package ch.kevinjordil.helion.ui.settings

import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.source.ExportUnavailableException
import ch.kevinjordil.helion.source.IngestResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncOutcomeTest {

    @Test
    fun `nothing configured yet surfaces as NotConfigured, not as a failure`() = runTest {
        val outcome = runSync(copyToCache = { null }, ingest = { IngestResult.NoSource })
        assertEquals(SyncOutcome.NotConfigured, outcome)
    }

    @Test
    fun `an unreadable configured file surfaces as Unavailable, distinct from NotConfigured`() = runTest {
        val outcome = runSync(
            copyToCache = { throw ExportUnavailableException("moved or deleted") },
            ingest = { error("must not be called: there is no path to ingest") },
        )
        assertEquals(SyncOutcome.Unavailable("moved or deleted"), outcome)
    }

    @Test
    fun `a successful pass reports how much arrived`() = runTest {
        val outcome = runSync(
            copyToCache = { "/cache/export.db" },
            ingest = { path ->
                assertEquals("/cache/export.db", path)
                IngestResult.Ingested(minutes = 12, points = 34)
            },
        )
        assertEquals(SyncOutcome.Ingested(12, 34), outcome)
    }

    @Test
    fun `a pass that ingested without triggering a refresh carries that through`() = runTest {
        val outcome = runSync(
            copyToCache = { "/cache/export.db" },
            ingest = { IngestResult.Ingested(minutes = 3, points = 1, refreshTriggered = false) },
        )
        assertEquals(SyncOutcome.Ingested(3, 1, refreshTriggered = false), outcome)
    }

    @Test
    fun `a failed ingestion pass carries its reason through`() = runTest {
        val outcome = runSync(
            copyToCache = { "/cache/export.db" },
            ingest = { IngestResult.Failed("Timed out waiting for Gadgetbridge to export") },
        )
        assertEquals(SyncOutcome.Failed("Timed out waiting for Gadgetbridge to export"), outcome)
    }

    @Test
    fun `not-configured and unavailable map to different messages`() {
        val (notConfiguredRes, _) = syncMessage(SyncOutcome.NotConfigured)
        val (unavailableRes, _) = syncMessage(SyncOutcome.Unavailable("whatever"))

        assertEquals(R.string.sync_result_not_configured, notConfiguredRes)
        assertEquals(R.string.sync_result_unavailable, unavailableRes)
        assert(notConfiguredRes != unavailableRes)
    }

    @Test
    fun `success message carries both counts as arguments`() {
        val (resId, args) = syncMessage(SyncOutcome.Ingested(minutes = 5, points = 7))
        assertEquals(R.string.sync_result_success, resId)
        assertEquals(listOf(5, 7), args)
    }

    @Test
    fun `an untriggered success maps to a different message than a triggered one`() {
        val (triggeredRes, _) = syncMessage(SyncOutcome.Ingested(5, 7, refreshTriggered = true))
        val (staleRes, args) = syncMessage(SyncOutcome.Ingested(5, 7, refreshTriggered = false))

        assertEquals(R.string.sync_result_success, triggeredRes)
        assertEquals(R.string.sync_result_success_stale, staleRes)
        assertEquals(listOf(5, 7), args)
        assert(triggeredRes != staleRes)
    }

    @Test
    fun `failure message carries the reason as an argument`() {
        val (resId, args) = syncMessage(SyncOutcome.Failed("boom"))
        assertEquals(R.string.sync_result_failed, resId)
        assertEquals(listOf("boom"), args)
    }
}
