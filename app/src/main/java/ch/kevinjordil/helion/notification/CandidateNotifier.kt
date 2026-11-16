package ch.kevinjordil.helion.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import ch.kevinjordil.helion.MainActivity
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.source.CandidateNotificationSink
import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.store.HeartRateSummary
import ch.kevinjordil.helion.ui.activity.sportLabelRes
import ch.kevinjordil.helion.ui.settings.NotificationPreference
import java.time.ZoneId

/** Carries a single candidate's id -- [MainActivity] opens that activity's detail directly. */
const val EXTRA_OPEN_ACTIVITY_ID = "ch.kevinjordil.helion.notification.OPEN_ACTIVITY_ID"

/** Set on a batch notification's tap -- [MainActivity] opens the Activités list, not one activity. */
const val EXTRA_OPEN_ACTIVITIES_LIST = "ch.kevinjordil.helion.notification.OPEN_ACTIVITIES_LIST"

/** The batch notification (several candidates at once): untagged, as it always was. */
private const val BATCH_NOTIFICATION_ID = 1
private const val TEST_NOTIFICATION_ID = 2

/**
 * Tag for everything about one activity: its proposal, then "sending", then the Strava
 * result -- all posted under `(CANDIDATE_TAG, activity id)`, so each step replaces the
 * previous one instead of stacking up. A tag keeps these ids apart from the fixed untagged
 * ids (1 to 4) the other notifications use.
 */
private const val CANDIDATE_TAG = "candidate"

/** The channel this class used before it had actions and content; deleted on first use. */
private const val LEGACY_CHANNEL_ID = "candidate_detection"

/**
 * The one place a detection pass' new candidates turn into an actual system notification --
 * see [ch.kevinjordil.helion.source.Ingestor]'s own "one notification per candidate, ever"
 * rule, which this class exists purely to serve without ever deciding on its own whether a
 * candidate has already had its chance (that bookkeeping is [ch.kevinjordil.helion.source.Ingestor]'s
 * job, driven by [ch.kevinjordil.helion.store.Activity.notified]).
 *
 * [notifyNewCandidates] returns `false` -- posting nothing at all -- whenever either guard
 * fails: [NotificationPreference.enabled] is off (Réglages), or Android's own runtime
 * permission (`POST_NOTIFICATIONS`, required from Android 13) has not been granted. Both
 * cases degrade identically: silent, with detection and the Activités list completely
 * unaffected. `checkSelfPermission` on a `minSdk` below 33 for a permission that is only a
 * runtime one from API 33 onward always reports granted, so this same check is correct
 * unconditionally, with no SDK-version branch needed.
 *
 * A single candidate's notification says what was detected -- sport when known, day and
 * time, duration, average and peak heart rate -- and carries the decision itself as actions
 * (see [CandidateActionReceiver]): "Publier sur Strava" when the sport is known and a server
 * is configured, "Confirmer" when the sport is known but there is nowhere to publish,
 * "Choisir le sport" (opens the activity) when it is not, and "Ignorer" always. Several
 * candidates at once still make exactly one notification, listing each of them, which opens
 * the Activités list.
 *
 * The channel's importance is [NotificationManager.IMPORTANCE_DEFAULT]: it shows in the
 * status bar and plays the default sound, without popping over whatever is on screen. The
 * earlier LOW channel ([LEGACY_CHANNEL_ID]) only appeared silently in the shade, easy to miss
 * for something the owner is expected to act on. Android does not let an app raise an
 * existing channel's importance, so this is a new channel id, and the old one is deleted.
 */
class CandidateNotifier(
    private val context: Context,
    private val preference: NotificationPreference,
    /** Average and peak heart rate over a window; null skips that part of the text. */
    private val heartRateSummary: (suspend (from: Long, to: Long) -> HeartRateSummary)? = null,
    /** Whether a custom server is configured, i.e. whether "Publier sur Strava" can work. */
    private val canPublish: () -> Boolean = { false },
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : CandidateNotificationSink {

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private fun permissionGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override suspend fun notifyNewCandidates(candidates: List<Activity>): Boolean {
        if (candidates.isEmpty()) return false
        if (!preference.enabled) return false
        if (!permissionGranted()) return false

        ensureChannel()
        if (candidates.size == 1) {
            val candidate = candidates.single()
            manager.notify(CANDIDATE_TAG, candidate.id.toInt(), singleNotification(candidate))
        } else {
            manager.notify(BATCH_NOTIFICATION_ID, batchNotification(candidates))
        }
        return true
    }

    /**
     * Réglages' own "send a test notification" action (see
     * [ch.kevinjordil.helion.ui.settings.NotificationsSettingsSection]) -- posted through
     * [CHANNEL_ID], the exact channel a real candidate notification uses, so a successful
     * test actually proves the real path rather than a synthetic stand-in. Deliberately
     * ignores [NotificationPreference.enabled]: the owner tapping this button is an explicit
     * request to test regardless of what that toggle is currently set to. Still returns
     * `false` -- posting nothing -- when the runtime permission is missing, the one guard
     * a test cannot honestly skip, since Android would silently drop the notification
     * itself in that case.
     */
    fun sendTestNotification(): Boolean {
        if (!permissionGranted()) return false

        ensureChannel()
        val notification = baseBuilder()
            .setContentTitle(context.getString(R.string.notification_test_title))
            .setContentText(context.getString(R.string.notification_test_text))
            .build()
        manager.notify(TEST_NOTIFICATION_ID, notification)
        return true
    }

    /** Removes [activityId]'s notification, if any -- once it has been decided, in the app or from the notification. */
    fun cancelCandidate(activityId: Long) {
        manager.cancel(CANDIDATE_TAG, activityId.toInt())
    }

    /** Replaces [activity]'s notification with "sending to Strava" while [PublishWorker] runs. */
    fun showPublishing(activity: Activity) {
        if (!permissionGranted()) return
        ensureChannel()
        val notification = baseBuilder()
            .setContentTitle(candidateTitle(activity))
            .setContentText(context.getString(R.string.notification_publish_sending))
            .setProgress(0, 0, true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(openActivity(activity.id))
            .build()
        manager.notify(CANDIDATE_TAG, activity.id.toInt(), notification)
    }

    /**
     * Replaces [activity]'s notification with the outcome of the send: [failureText] null
     * means it reached the server, otherwise it is the French reason to show. Either way a
     * tap opens the activity, where the full server answer is shown and a send can be retried.
     */
    fun showPublishResult(activity: Activity, failureText: String?) {
        if (!permissionGranted()) return
        ensureChannel()
        val title = context.getString(
            if (failureText == null) R.string.notification_publish_done_title else R.string.notification_publish_failed_title,
        )
        val text = failureText ?: candidateSummaryLine(activity, heartRate = null)
        val notification = baseBuilder()
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setContentIntent(openActivity(activity.id))
            .build()
        manager.notify(CANDIDATE_TAG, activity.id.toInt(), notification)
    }

    private fun ensureChannel() {
        val manager = manager
        if (manager.getNotificationChannel(LEGACY_CHANNEL_ID) != null) manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private suspend fun singleNotification(candidate: Activity): Notification {
        val heartRate = heartRateSummary?.invoke(candidate.startTimestamp, candidate.endTimestamp)
        val summary = candidateSummaryLine(candidate, heartRate)
        val hint = context.getString(
            when {
                candidate.sport == null -> R.string.notification_candidate_hint_no_sport
                canPublish() -> R.string.notification_candidate_hint_publish
                else -> R.string.notification_candidate_hint_no_server
            },
        )
        val builder = baseBuilder()
            .setContentTitle(candidateTitle(candidate))
            .setContentText(summary)
            .setStyle(Notification.BigTextStyle().bigText("$summary\n$hint"))
            .setContentIntent(openActivity(candidate.id))

        when {
            candidate.sport == null ->
                builder.addAction(action(R.string.notification_action_choose_sport, openActivity(candidate.id)))
            canPublish() ->
                builder.addAction(action(R.string.notification_action_publish, actionIntent(CandidateActionReceiver.ACTION_PUBLISH, candidate.id)))
            else ->
                builder.addAction(action(R.string.notification_action_confirm, actionIntent(CandidateActionReceiver.ACTION_CONFIRM, candidate.id)))
        }
        builder.addAction(action(R.string.notification_action_dismiss, actionIntent(CandidateActionReceiver.ACTION_DISMISS, candidate.id)))
        return builder.build()
    }

    private suspend fun batchNotification(candidates: List<Activity>): Notification {
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_ACTIVITIES_LIST, true)
        }
        val style = Notification.InboxStyle()
        candidates.forEach { candidate ->
            val heartRate = heartRateSummary?.invoke(candidate.startTimestamp, candidate.endTimestamp)
            style.addLine("${candidateTitle(candidate)} · ${candidateSummaryLine(candidate, heartRate)}")
        }
        return baseBuilder()
            .setContentTitle(context.getString(R.string.notification_candidate_batch_title))
            .setContentText(context.getString(R.string.notification_candidate_batch_text, candidates.size))
            .setStyle(style)
            .setContentIntent(pendingActivity(contentIntent, requestCode = 0))
            .build()
    }

    /** The sport when known, otherwise "Activité détectée". */
    private fun candidateTitle(activity: Activity): String =
        activity.sport?.let { context.getString(sportLabelRes(it)) }
            ?: context.getString(R.string.notification_candidate_title)

    private fun candidateSummaryLine(activity: Activity, heartRate: HeartRateSummary?): String =
        candidateSummary(
            strings = AndroidCandidateStrings(context),
            start = activity.startTimestamp,
            end = activity.endTimestamp,
            heartRate = heartRate,
            zone = zone,
            nowSeconds = now(),
        )

    private fun action(labelRes: Int, intent: PendingIntent): Notification.Action =
        Notification.Action.Builder(null, context.getString(labelRes), intent).build()

    private fun openActivity(activityId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_ACTIVITY_ID, activityId)
        }
        return pendingActivity(intent, requestCode = activityId.toInt())
    }

    private fun actionIntent(action: String, activityId: Long): PendingIntent {
        val intent = Intent(context, CandidateActionReceiver::class.java).apply {
            this.action = action
            putExtra(CandidateActionReceiver.EXTRA_ACTIVITY_ID, activityId)
        }
        // The request code alone does not tell two actions of the same activity apart for
        // PendingIntent matching (the action string does), but it does tell activities apart.
        return PendingIntent.getBroadcast(
            context,
            activityId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun baseBuilder(): Notification.Builder = Notification.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setAutoCancel(true)

    private fun pendingActivity(intent: Intent, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        /**
         * Public -- unlike the notification ids -- because Réglages' own diagnostics need it
         * too: to read this channel's actual importance back from [NotificationManager], and
         * to send the owner straight to this one channel's own settings
         * (`Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS`) rather than the app's general
         * notification settings, when the channel itself is what is blocking him.
         */
        const val CHANNEL_ID = "candidate_review"
    }
}
