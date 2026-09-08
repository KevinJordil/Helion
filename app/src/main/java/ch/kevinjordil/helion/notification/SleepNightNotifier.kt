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
import ch.kevinjordil.helion.source.SleepNightNotificationSink
import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.store.NotifiedSleepNight
import ch.kevinjordil.helion.ui.metric.Reading
import ch.kevinjordil.helion.ui.quality.Baseline
import ch.kevinjordil.helion.ui.quality.computeBaseline
import ch.kevinjordil.helion.ui.quality.personalBaselineMessage
import ch.kevinjordil.helion.ui.quality.placeAgainstBaseline
import ch.kevinjordil.helion.ui.quality.referenceForSleepDuration
import ch.kevinjordil.helion.ui.quality.referenceMessage
import ch.kevinjordil.helion.ui.settings.SleepNotificationPreference
import ch.kevinjordil.helion.ui.sleep.SLEEP_NOTIFICATION_STALE_AFTER_SECONDS
import ch.kevinjordil.helion.ui.sleep.SleepEpisode
import ch.kevinjordil.helion.ui.sleep.SleepEpisodeKind
import ch.kevinjordil.helion.ui.sleep.SleepReader
import ch.kevinjordil.helion.ui.sleep.sleepNightToNotify
import java.time.ZoneId

/** Set on the sleep-summary notification's own content intent -- [MainActivity] opens Sommeil directly. */
const val EXTRA_OPEN_SLEEP = "ch.kevinjordil.helion.notification.OPEN_SLEEP"

private const val SLEEP_NOTIFICATION_ID = 3
private const val SLEEP_TEST_NOTIFICATION_ID = 4

/**
 * The one place a completed night turns into an actual system notification -- see
 * [ch.kevinjordil.helion.source.Ingestor]'s own "an ingest pass that completes a night,
 * never a clock alarm" trigger, and [SleepNightNotificationSink]'s kdoc for why this class
 * owns the whole decision (which night, dedup, freshness) rather than [Ingestor] doing any
 * of it.
 *
 * A separate channel ([CHANNEL_ID]) and a separate Réglages toggle
 * ([SleepNotificationPreference]) from [CandidateNotifier]'s own: the owner asked to be
 * able to silence one without the other, and a shared channel or a shared switch would
 * make that impossible. The runtime `POST_NOTIFICATIONS` permission is still the one
 * Android exposes for the whole app, so it is checked the same way [CandidateNotifier]
 * does, with no channel-specific equivalent to check alongside it.
 *
 * [checkForCompletedNight] degrades to silence exactly like [CandidateNotifier] does: the
 * setting off, or the permission refused, both mean nothing is posted and nothing is
 * marked notified -- the night keeps its one remaining chance for a later pass, as long as
 * that pass still falls inside [ch.kevinjordil.helion.ui.sleep.SLEEP_NOTIFICATION_STALE_AFTER_SECONDS]
 * of its own wake time. Past that window the night is simply never notified for at all --
 * unlike a candidate activity, "how you slept last night" said several days late is not
 * news worth surfacing, per this feature's own spec.
 *
 * The message states two facts and nothing else: the duration, and where it sits against
 * the owner's own recent nights and against the 7-9h reference range -- reusing exactly
 * the same [personalBaselineMessage]/[referenceMessage] wording Sommeil's own detail card
 * shows for a single night, so the notification never says anything the screen it opens
 * would disagree with. No verdict, no "bon"/"mauvais" -- see those functions' own kdoc for
 * why position, not judgment, is all either can ever say.
 */
class SleepNightNotifier(
    private val context: Context,
    private val database: HelionDatabase,
    private val preference: SleepNotificationPreference,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long,
) : SleepNightNotificationSink {

    override suspend fun checkForCompletedNight() {
        if (!preference.enabled) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val nowSeconds = now()
        val nights = SleepReader(database, zone).loadNights(nowSeconds)

        // sleepNightToNotify itself is a plain, suspend-free function (kept that way so it
        // stays trivially unit-testable) -- the notified check it needs is therefore
        // resolved up front here, suspending only for the handful of nights that could
        // plausibly qualify (already narrowed by freshness) rather than for every night in
        // [nights].
        val plausible = nights.filter {
            it.kind == SleepEpisodeKind.NIGHT && !it.isInProgress && !it.hasDataGap &&
                (nowSeconds - it.wokeAt) in 0..SLEEP_NOTIFICATION_STALE_AFTER_SECONDS
        }
        val alreadyNotified = plausible.filter { database.notifiedSleepNights().isNotified(it.wokeAt) }.map { it.wokeAt }.toSet()

        val night = sleepNightToNotify(nights, nowSeconds, isAlreadyNotified = { wokeAt -> wokeAt in alreadyNotified })
            ?: return

        // The personal baseline is computed the same way SleepScreen's own is: from every
        // trustworthy (not in-progress, not gappy) night this reader loaded, never from
        // the single night being reported on.
        val history = nights
            .filterNot { it.isInProgress || it.hasDataGap }
            .map { Reading(it.wokeAt, it.durationAsleepMinutes / 60.0) }
        val baseline = computeBaseline(history)

        ensureChannel()
        val notification = buildNotification(night, baseline)
        context.getSystemService(NotificationManager::class.java).notify(SLEEP_NOTIFICATION_ID, notification)
        database.notifiedSleepNights().markNotified(NotifiedSleepNight(night.wokeAt))
    }

    /**
     * Réglages' own "send a test notification" action for this channel -- posted through
     * [CHANNEL_ID], the exact channel a real sleep-summary notification uses. Deliberately
     * ignores [SleepNotificationPreference.enabled], mirroring [CandidateNotifier.sendTestNotification]'s
     * own reasoning: the owner tapping this button is an explicit request to test
     * regardless of the toggle. Still returns `false` when the runtime permission is
     * missing -- the one guard a test cannot honestly skip.
     */
    fun sendTestNotification(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false

        ensureChannel()
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentTitle(context.getString(R.string.notification_test_title))
            .setContentText(context.getString(R.string.notification_test_text))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(SLEEP_TEST_NOTIFICATION_ID, notification)
        return true
    }

    private fun buildNotification(night: SleepEpisode, baseline: Baseline?): Notification {
        val hours = night.durationAsleepMinutes / 60
        val minutes = night.durationAsleepMinutes % 60
        val durationText = context.getString(R.string.sleep_duration_format, hours.toInt(), minutes.toInt())

        val (personalRes, _) = personalBaselineMessage(placeAgainstBaseline(night.durationAsleepMinutes / 60.0, baseline))
        val (referenceRes, _) = referenceMessage("sleep_duration", referenceForSleepDuration(night.durationAsleepMinutes / 60.0))

        val text = context.getString(
            R.string.sleep_notification_text,
            durationText,
            context.getString(personalRes),
            context.getString(referenceRes),
        )

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_SLEEP, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            /* requestCode = */ night.wokeAt.toInt(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentTitle(context.getString(R.string.sleep_notification_title))
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.sleep_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.sleep_notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        /**
         * Public -- unlike the notification ids -- for the same reason
         * [CandidateNotifier.CHANNEL_ID] is: Réglages' own diagnostics need it to read this
         * channel's actual importance back from [NotificationManager], and to send the
         * owner straight to this one channel's own settings when the channel itself is
         * what is blocking him.
         */
        const val CHANNEL_ID = "sleep_summary"
    }
}
