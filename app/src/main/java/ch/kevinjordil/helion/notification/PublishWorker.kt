package ch.kevinjordil.helion.notification

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ch.kevinjordil.helion.HelionApp
import ch.kevinjordil.helion.customserver.CustomServerPublisher
import ch.kevinjordil.helion.store.PublicationTarget
import ch.kevinjordil.helion.ui.activity.customServerFailureReasonArgs
import ch.kevinjordil.helion.ui.activity.customServerFailureReasonRes

/**
 * Sends one activity to the owner's server (which relays it to Strava) for the notification's
 * "Publier sur Strava" button, then replaces the notification with the result.
 *
 * A worker rather than the broadcast receiver itself: a receiver only has a few seconds,
 * while this is a network request that may first have to wait for a connection (the
 * [NetworkType.CONNECTED] constraint). Never retried automatically: a failure is shown to the
 * owner, and a new send from the activity screen is one tap. A repeat is harmless anyway --
 * the server recognises the same `external_id` (see [CustomServerPublisher]).
 */
class PublishWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val activityId = inputData.getLong(KEY_ACTIVITY_ID, -1L)
        if (activityId < 0) return Result.success()
        val container = (applicationContext as HelionApp).container

        val result = container.customServerPublisher.send(activityId)
        val activity = container.database.activities().get(activityId) ?: return Result.success()
        val failureText = when (result) {
            is CustomServerPublisher.Result.Sent -> null
            is CustomServerPublisher.Result.Failed -> {
                val detail = container.database.publications().get(activityId, PublicationTarget.CUSTOM_SERVER)?.lastErrorDetail
                applicationContext.getString(
                    customServerFailureReasonRes(result.reason),
                    *customServerFailureReasonArgs(result.reason, detail).toTypedArray(),
                )
            }
        }
        container.candidateNotifier.showPublishResult(activity, failureText)
        return Result.success()
    }

    companion object {
        private const val KEY_ACTIVITY_ID = "activity_id"

        /** One send per activity at a time: a second tap while one is pending does nothing. */
        fun enqueue(context: Context, activityId: Long) {
            val request = OneTimeWorkRequestBuilder<PublishWorker>()
                .setInputData(workDataOf(KEY_ACTIVITY_ID to activityId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("helion-publish-$activityId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
