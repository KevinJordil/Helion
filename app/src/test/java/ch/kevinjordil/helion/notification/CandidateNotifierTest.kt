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
        sport = SportType.OTHER,
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
}
