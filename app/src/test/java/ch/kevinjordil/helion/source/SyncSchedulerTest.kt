package ch.kevinjordil.helion.source

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncSchedulerTest {

    @Test
    fun `the background sync runs once every four hours`() {
        val spec = SyncScheduler.periodicRequest().workSpec

        assertEquals(TimeUnit.HOURS.toMillis(4), spec.intervalDuration)
        // Flex equal to the interval: WorkManager may run it anywhere in the period, but
        // still only once per period.
        assertEquals(TimeUnit.HOURS.toMillis(4), spec.flexDuration)
    }

    @Test
    fun `the background pass waits long enough for a four-hour Bluetooth fetch, well within WorkManager's limit`() {
        val waitMillis = SyncWorker.BACKGROUND_SYNC_TIMEOUT_MILLIS + Ingestor.EXPORT_TIMEOUT_MILLIS
        assertEquals(true, waitMillis < TimeUnit.MINUTES.toMillis(10))
    }
}
