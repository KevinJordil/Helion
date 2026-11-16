package ch.kevinjordil.helion.notification

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.store.ActivityOrigin
import ch.kevinjordil.helion.store.ActivityStatus
import ch.kevinjordil.helion.store.SportType
import ch.kevinjordil.helion.ui.settings.NotificationPreference
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The two ways a candidate notification degrades to silence (setting off, permission
 * refused -- see [CandidateNotifier]'s own kdoc) and the batching rule: several candidates
 * become exactly one system notification, never one each.
 */
@RunWith(RobolectricTestRunner::class)
class CandidateNotifierTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private val notificationManager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private fun candidate(id: Long) = Activity(
        id = id,
        startTimestamp = 1_000,
        endTimestamp = 2_000,
        sport = SportType.WORKOUT,
        title = null,
        notes = null,
        origin = ActivityOrigin.DETECTED,
        status = ActivityStatus.CANDIDATE,
    )

    @Test
    fun `the setting turned off posts nothing, silently`() = runTest {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val preference = NotificationPreference(context).apply { enabled = false }
        val notifier = CandidateNotifier(context, preference)

        val posted = notifier.notifyNewCandidates(listOf(candidate(1)))

        assertFalse(posted)
        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
    }

    @Test
    fun `a refused permission posts nothing, silently, even with the setting on`() = runTest {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val preference = NotificationPreference(context).apply { enabled = true }
        val notifier = CandidateNotifier(context, preference)

        val posted = notifier.notifyNewCandidates(listOf(candidate(1)))

        assertFalse(posted)
        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
    }

    @Test
    fun `a single candidate gets its own notification`() = runTest {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val preference = NotificationPreference(context).apply { enabled = true }
        val notifier = CandidateNotifier(context, preference)

        val posted = notifier.notifyNewCandidates(listOf(candidate(42)))

        assertTrue(posted)
        assertEquals(1, shadowOf(notificationManager).allNotifications.size)
    }

    @Test
    fun `several candidates from one pass are summarised into exactly one notification`() = runTest {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val preference = NotificationPreference(context).apply { enabled = true }
        val notifier = CandidateNotifier(context, preference)

        val posted = notifier.notifyNewCandidates(listOf(candidate(1), candidate(2), candidate(3)))

        assertTrue(posted)
        assertEquals(1, shadowOf(notificationManager).allNotifications.size)
    }

    private fun postedActions(): List<String> =
        shadowOf(notificationManager).allNotifications.single().actions.orEmpty().map { it.title.toString() }

    @Test
    fun `a candidate with a known sport and a configured server offers to publish it`() = runTest {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val preference = NotificationPreference(context).apply { enabled = true }
        val notifier = CandidateNotifier(context, preference, canPublish = { true })

        notifier.notifyNewCandidates(listOf(candidate(7)))

        assertEquals(listOf("Publier sur Strava", "Ignorer"), postedActions())
        val posted = shadowOf(notificationManager).allNotifications.single()
        assertEquals("Entraînement", posted.extras.getString(android.app.Notification.EXTRA_TITLE))
    }

    @Test
    fun `without a server the notification offers to confirm instead`() = runTest {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val preference = NotificationPreference(context).apply { enabled = true }
        val notifier = CandidateNotifier(context, preference, canPublish = { false })

        notifier.notifyNewCandidates(listOf(candidate(7)))

        assertEquals(listOf("Confirmer", "Ignorer"), postedActions())
    }

    @Test
    fun `a candidate without a sport asks for one first`() = runTest {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val preference = NotificationPreference(context).apply { enabled = true }
        val notifier = CandidateNotifier(context, preference, canPublish = { true })

        notifier.notifyNewCandidates(listOf(candidate(7).copy(sport = null)))

        assertEquals(listOf("Choisir le sport", "Ignorer"), postedActions())
        val posted = shadowOf(notificationManager).allNotifications.single()
        assertEquals("Activité détectée", posted.extras.getString(android.app.Notification.EXTRA_TITLE))
    }

    @Test
    fun `the text carries the heart rate when the window has readings`() = runTest {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val preference = NotificationPreference(context).apply { enabled = true }
        val notifier = CandidateNotifier(
            context,
            preference,
            heartRateSummary = { _, _ -> ch.kevinjordil.helion.store.HeartRateSummary(maxHeartRate = 168, averageHeartRate = 140.2) },
        )

        notifier.notifyNewCandidates(listOf(candidate(7)))

        val text = shadowOf(notificationManager).allNotifications.single().extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertTrue(text, text.endsWith("FC moy. 140, max 168 bpm"))
    }

    @Test
    fun `the channel is audible, and the old silent one is removed`() = runTest {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notificationManager.createNotificationChannel(
            android.app.NotificationChannel("candidate_detection", "old", NotificationManager.IMPORTANCE_LOW),
        )
        val preference = NotificationPreference(context).apply { enabled = true }

        CandidateNotifier(context, preference).notifyNewCandidates(listOf(candidate(7)))

        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, notificationManager.getNotificationChannel(CandidateNotifier.CHANNEL_ID).importance)
        assertEquals(null, notificationManager.getNotificationChannel("candidate_detection"))
    }

    @Test
    fun `cancelling a candidate removes its notification`() = runTest {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val preference = NotificationPreference(context).apply { enabled = true }
        val notifier = CandidateNotifier(context, preference)
        notifier.notifyNewCandidates(listOf(candidate(7)))

        notifier.cancelCandidate(7)

        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
    }
}
