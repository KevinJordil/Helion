package ch.kevinjordil.helion.healthconnect

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ch.kevinjordil.helion.HelionApp

private const val UNIQUE_WORK_NAME = "helion-health-connect-export"

/**
 * Runs one [HealthConnectExporter.export] pass in the background. See
 * [enqueueHealthConnectExport]'s own kdoc for when this is actually enqueued -- always a one-off,
 * never periodic: there is no fixed schedule to keep, only "something changed, so check".
 *
 * A [androidx.work.CoroutineWorker] cooperates with WorkManager's own cancellation (the app
 * being force-stopped, the OS reclaiming the process, a newer request replacing this one --
 * see [HealthConnectExportTrigger]'s own kdoc on [ExistingWorkPolicy.REPLACE]) through plain
 * coroutine cancellation, exactly what [HealthConnectExporter.export] itself already
 * respects by rethrowing [kotlinx.coroutines.CancellationException] rather than reporting
 * it as a failure -- see that class' own kdoc.
 */
class HealthConnectSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as HelionApp).container
        return when (container.healthConnectExporter.export()) {
            is HealthConnectExportOutcome.Failed -> Result.retry()
            else -> Result.success()
        }
    }
}

/**
 * Enqueues [HealthConnectSyncWorker], the one place either of this feature's two triggers
 * (see this feature's own top-level brief: after every successful ingest, and the "Exporter
 * maintenant" button in Réglages) actually goes through -- [ch.kevinjordil.helion.source.Ingestor]
 * itself never awaits a Health Connect write, so a slow or stuck export can never slow the
 * ingest path down.
 *
 * [ExistingWorkPolicy.REPLACE]: a new trigger firing while a previous pass is still running
 * (an ingest pass finishing right as the owner also taps "Exporter maintenant") cancels that
 * older attempt in favour of the new one, rather than queuing a second pass behind it --
 * exactly the "cancellable" requirement this feature's own brief asks for, and harmless
 * either way since every write is idempotent on its own client record id.
 */
fun enqueueHealthConnectExport(context: Context) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        UNIQUE_WORK_NAME,
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<HealthConnectSyncWorker>().build(),
    )
}
