package ch.kevinjordil.helion.healthconnect

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_800_000_000L

class HealthConnectBatchingTest {

    /** A cheap HeartRateRecord with [sampleCount] samples, one second apart -- the one record kind here whose estimated size actually varies with content. */
    private fun heartRateRecord(id: String, sampleCount: Int): HeartRateRecord {
        val samples = (0 until sampleCount).map { HeartRateRecord.Sample(time = Instant.ofEpochSecond(it.toLong()), beatsPerMinute = 70L) }
        return HeartRateRecord(
            startTime = Instant.ofEpochSecond(0),
            startZoneOffset = null,
            endTime = Instant.ofEpochSecond((sampleCount - 1).coerceAtLeast(0).toLong()),
            endZoneOffset = null,
            samples = samples,
            metadata = healthConnectMetadata("helion-heart-rate-$id", NOW),
        )
    }

    @Test
    fun `a small export -- well under the byte budget -- goes in a single batch`() {
        val records: List<Record> = (1..5).map { heartRateRecord("$it", sampleCount = 10) }
        val batches = batchRecordsBySize(records)
        assertEquals(1, batches.size)
        assertEquals(records.size, batches.first().size)
    }

    @Test
    fun `a large export is split into several batches, each within the byte budget`() {
        // Every record here is far too small on its own to hit the budget -- what forces a
        // split is their combined total, exactly the shape of the real bug (many small daily
        // records adding up to well past 5MB, not one oversized record).
        val records: List<Record> = (1..500).map { heartRateRecord("$it", sampleCount = 200) }
        val batches = batchRecordsBySize(records)

        assertTrue("expected more than one batch for a large export", batches.size > 1)
        for (batch in batches) {
            val totalBytes = batch.sumOf { estimateRecordBytes(it) }
            assertTrue("batch of $totalBytes bytes exceeds the budget", totalBytes <= HEALTH_CONNECT_BATCH_BYTE_BUDGET)
        }
        // No record lost or duplicated across the split.
        assertEquals(records.size, batches.sumOf { it.size })
        assertEquals(records.map { it.metadata.clientRecordId }, batches.flatten().map { it.metadata.clientRecordId })
    }

    @Test
    fun `an empty export produces no batches at all`() {
        assertEquals(emptyList<List<Record>>(), batchRecordsBySize(emptyList()))
    }

    @Test
    fun `a single record whose own estimate exceeds the budget still gets a batch of its own`() {
        val oversized = heartRateRecord("huge", sampleCount = 1_000_000)
        val batches = batchRecordsBySize(listOf(oversized), byteBudget = 1_000)
        assertEquals(1, batches.size)
        assertEquals(listOf(oversized), batches.first())
    }

    @Test
    fun `the byte budget itself leaves real headroom under Health Connect's own hard chunk limit`() {
        assertTrue(HEALTH_CONNECT_BATCH_BYTE_BUDGET < HEALTH_CONNECT_MAX_CHUNK_BYTES)
    }
}
