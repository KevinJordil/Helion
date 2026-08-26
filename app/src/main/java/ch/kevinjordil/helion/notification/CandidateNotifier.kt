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
import ch.kevinjordil.helion.ui.settings.NotificationPreference

/** Carries a single candidate's id -- [MainActivity] opens that activity's detail directly. */
const val EXTRA_OPEN_ACTIVITY_ID = "ch.kevinjordil.helion.notification.OPEN_ACTIVITY_ID"

/** Set on a batch notification's tap -- [MainActivity] opens the Activités list, not one activity. */
const val EXTRA_OPEN_ACTIVITIES_LIST = "ch.kevinjordil.helion.notification.OPEN_ACTIVITIES_LIST"

private const val CHANNEL_ID = "candidate_detection"
private const val NOTIFICATION_ID = 1

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
 * The channel's importance is [NotificationManager.IMPORTANCE_LOW]: a line in the shade,
 * no sound, no heads-up pop-over. This is a proposal to review whenever convenient, never
 * an alarm -- the entire reason this feature exists.
 */
class CandidateNotifier(
    private val context: Context,
    private val preference: NotificationPreference,
) : CandidateNotificationSink {

    override suspend fun notifyNewCandidates(candidates: List<Activity>): Boolean {
        if (candidates.isEmpty()) return false
        if (!preference.enabled) return false
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false

        ensureChannel()
        val notification = if (candidates.size == 1) singleNotification(candidates.single()) else batchNotification(candidates.size)
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        return true
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun singleNotification(candidate: Activity): Notification {
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_ACTIVITY_ID, candidate.id)
        }
        return baseBuilder()
            .setContentTitle(context.getString(R.string.notification_candidate_title))
            .setContentText(context.getString(R.string.notification_candidate_text))
            .setContentIntent(pendingIntentFor(contentIntent, requestCode = candidate.id.toInt()))
            .build()
    }

    private fun batchNotification(count: Int): Notification {
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_ACTIVITIES_LIST, true)
        }
        return baseBuilder()
            .setContentTitle(context.getString(R.string.notification_candidate_batch_title))
            .setContentText(context.getString(R.string.notification_candidate_batch_text, count))
            .setContentIntent(pendingIntentFor(contentIntent, requestCode = 0))
            .build()
    }

    private fun baseBuilder(): Notification.Builder = Notification.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setAutoCancel(true)

    private fun pendingIntentFor(intent: Intent, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
