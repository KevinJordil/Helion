package ch.kevinjordil.helion.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ch.kevinjordil.helion.HelionApp
import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.store.ActivityDao
import ch.kevinjordil.helion.store.ActivityStatus
import kotlinx.coroutines.launch

/**
 * What a candidate notification's action buttons do. Kept apart from the receiver so the
 * decisions themselves are testable against a real (in-memory) database.
 *
 * Every action first re-reads the activity: the notification may be hours old, and the owner
 * may already have decided in the app in the meantime. A decision made there is never
 * overridden by an old button -- the notification is simply removed.
 */
class CandidateActions(
    private val activities: ActivityDao,
    private val cancelNotification: (Long) -> Unit,
    private val showPublishing: (Activity) -> Unit,
    private val enqueuePublish: (Long) -> Unit,
) {

    /** "Confirmer": the candidate becomes [ActivityStatus.CONFIRMED]. */
    suspend fun confirm(activityId: Long) {
        decideCandidate(activityId, ActivityStatus.CONFIRMED)
        cancelNotification(activityId)
    }

    /** "Ignorer": the candidate becomes [ActivityStatus.DISMISSED], so it is never proposed again. */
    suspend fun dismiss(activityId: Long) {
        decideCandidate(activityId, ActivityStatus.DISMISSED)
        cancelNotification(activityId)
    }

    /**
     * "Publier sur Strava": confirms the candidate (publishing is a stronger yes than
     * confirming), shows "sending" in place of the proposal, and hands the send to
     * [PublishWorker], which waits for a network connection if there is none. A dismissed
     * activity is not published.
     */
    suspend fun publish(activityId: Long) {
        val activity = activities.get(activityId)
        if (activity == null || activity.status == ActivityStatus.DISMISSED) {
            cancelNotification(activityId)
            return
        }
        val confirmed = if (activity.status == ActivityStatus.CANDIDATE) {
            activity.copy(status = ActivityStatus.CONFIRMED, provisional = false).also { activities.update(it) }
        } else {
            activity
        }
        showPublishing(confirmed)
        enqueuePublish(activityId)
    }

    /**
     * Moves a still-undecided candidate to [status]. Clears [Activity.provisional], exactly as
     * the detail screen's own save does: an owner's decision is final and a later detection
     * pass must never grow the row again.
     */
    private suspend fun decideCandidate(activityId: Long, status: ActivityStatus) {
        val activity = activities.get(activityId) ?: return
        if (activity.status != ActivityStatus.CANDIDATE) return
        activities.update(activity.copy(status = status, provisional = false))
    }
}

/**
 * Receives a candidate notification's action buttons (see [CandidateNotifier]). Not exported:
 * only this app's own PendingIntents can reach it.
 *
 * [goAsync] keeps the process alive while the database write runs on the application scope;
 * the network send itself is not done here but in [PublishWorker], since a broadcast receiver
 * only has a few seconds.
 */
class CandidateActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val activityId = intent.getLongExtra(EXTRA_ACTIVITY_ID, -1L)
        if (activityId < 0) return
        val container = (context.applicationContext as HelionApp).container
        val actions = CandidateActions(
            activities = container.database.activities(),
            cancelNotification = container.candidateNotifier::cancelCandidate,
            showPublishing = container.candidateNotifier::showPublishing,
            enqueuePublish = { id -> PublishWorker.enqueue(context, id) },
        )
        val pending = goAsync()
        container.applicationScope.launch {
            try {
                when (intent.action) {
                    ACTION_CONFIRM -> actions.confirm(activityId)
                    ACTION_DISMISS -> actions.dismiss(activityId)
                    ACTION_PUBLISH -> actions.publish(activityId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CONFIRM = "ch.kevinjordil.helion.notification.CONFIRM"
        const val ACTION_DISMISS = "ch.kevinjordil.helion.notification.DISMISS"
        const val ACTION_PUBLISH = "ch.kevinjordil.helion.notification.PUBLISH"
        const val EXTRA_ACTIVITY_ID = "ch.kevinjordil.helion.notification.ACTIVITY_ID"
    }
}
