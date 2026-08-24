package ch.kevinjordil.helion.source

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ch.kevinjordil.helion.HelionApp
import java.util.concurrent.TimeUnit

/** Runs one ingestion pass in the background. */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as HelionApp).container

        // copyToCache() throws ExportUnavailableException when a location IS configured
        // but the file behind it can no longer be read (moved, deleted, or the permission
        // was lost) -- distinct from returning null, which means nothing has been
        // configured yet and there is simply nothing to do this pass.
        val path = try {
            container.exportLocation.copyToCache()
        } catch (e: ExportUnavailableException) {
            return Result.retry()
        } ?: return Result.success()

        return when (container.ingestor.ingest(path)) {
            is IngestResult.Failed -> Result.retry()
            else -> Result.success()
        }
    }
}

object SyncScheduler {

    /** Every 30 minutes: the device only reports one sample per minute, so more is waste. */
    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "helion-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES).build(),
        )
    }
}
