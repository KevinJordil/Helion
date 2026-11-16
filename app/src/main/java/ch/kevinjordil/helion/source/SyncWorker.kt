package ch.kevinjordil.helion.source

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ch.kevinjordil.helion.HelionApp
import java.util.concurrent.TimeUnit

/**
 * The one background sync: a full [Ingestor] pass -- ask Gadgetbridge to fetch from the strap,
 * wait for that fetch to finish, ask it to export, then read the export.
 *
 * Always [Result.success], even when the pass failed: [Result.retry] would make WorkManager
 * run it again with backoff inside the same period, i.e. extra syncs. A failed pass is
 * recorded in [ch.kevinjordil.helion.store.SyncState.lastError] (and the attempt itself in
 * `lastBackgroundSyncAttempt`), and the next attempt is the next period's.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as HelionApp).container
        container.ingestor.ingest(
            source = container.exportLocation,
            background = true,
            syncTimeoutMillis = BACKGROUND_SYNC_TIMEOUT_MILLIS,
        )
        return Result.success()
    }

    companion object {
        /**
         * How long the pass waits for Gadgetbridge's ACTIVITY_SYNC_FINISH: four hours of
         * strap data over Bluetooth, plus connecting first. Three minutes leaves plenty of room
         * while keeping the whole pass (this wait, plus at most 30 s for the export) far below
         * WorkManager's 10-minute limit per run. When the strap is out of range, or "Broadcast
         * on activity sync finish" is off in Gadgetbridge, no broadcast comes and the pass
         * simply goes on after this wait.
         */
        const val BACKGROUND_SYNC_TIMEOUT_MILLIS = 180_000L
    }
}

object SyncScheduler {

    const val UNIQUE_WORK_NAME = "helion-sync"

    /** One background sync every four hours. */
    const val INTERVAL_HOURS = 4L

    /**
     * Called on every process start (see [HelionApp.onCreate]).
     *
     * [ExistingPeriodicWorkPolicy.UPDATE], not KEEP: KEEP would leave a phone that already
     * had the old 30-minute schedule on it forever. UPDATE replaces the request's spec but
     * carries over the existing work's last enqueue time and period count (checked in
     * WorkManager 2.10.5's `WorkerUpdater.updateWorkImpl`), so calling this on every start
     * does not push the next run back: it stays four hours after the previous one.
     *
     * WorkManager guarantees at most one run per period, not an exact time: Android's
     * battery management (Doze, and Samsung's "sleeping apps") can delay a run.
     */
    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest(),
        )
    }

    internal fun periodicRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<SyncWorker>(INTERVAL_HOURS, TimeUnit.HOURS).build()
}
