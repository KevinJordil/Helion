package ch.kevinjordil.helion.notification

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.kevinjordil.helion.source.SleepStage
import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.ui.settings.SleepNotificationPreference
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * [SleepNightNotifier.checkForCompletedNight]'s end-to-end behaviour against a real,
 * in-memory [HelionDatabase]: a completed night is posted through its own channel exactly
 * once, ever, and the two ways it degrades to silence (the owner's setting off, the
 * runtime permission refused) never crash and never mark anything notified.
 */
@RunWith(RobolectricTestRunner::class)
class SleepNightNotifierTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val zurich = ZoneId.of("Europe/Zurich")
    private val anchor: Long = LocalDate.of(2024, 1, 1).atStartOfDay(zurich).toEpochSecond()

    private lateinit var db: HelionDatabase

    private val notificationManager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    /** 23:00 local -> 400 minutes asleep -> one AWAKE minute right after, so the night is complete, not in progress. */
    private val bedtime = anchor + 23L * 60 * 60
    private val wokeAt = bedtime + 400L * 60

    private fun seedCompletedNight() {
        val asleep = (0..400L).map { i ->
            MinuteSample(timestamp = bedtime + i * 60, steps = 0, intensity = 5, rawKind = null, heartRate = 55, sleepStage = SleepStage.ASLEEP)
        }
        val awake = MinuteSample(timestamp = wokeAt + 60, steps = 0, intensity = 20, rawKind = null, heartRate = 70, sleepStage = SleepStage.AWAKE)
        runTest { db.minuteSamples().upsertAll(asleep + awake) }
    }

    private fun notifierAt(now: Long, preference: SleepNotificationPreference = SleepNotificationPreference(context)) =
        SleepNightNotifier(context, db, preference, zurich) { now }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, HelionDatabase::class.java).allowMainThreadQueries().build()
        seedCompletedNight()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a completed, fresh night posts through the sleep channel and is marked notified`() = runTest {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifier = notifierAt(now = wokeAt + 3_600)

        notifier.checkForCompletedNight()

        val posted = shadowOf(notificationManager).allNotifications
        assertEquals(1, posted.size)
        assertTrue(db.notifiedSleepNights().isNotified(wokeAt))
    }

    @Test
    fun `the same night is never notified a second time`() = runTest {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifier = notifierAt(now = wokeAt + 3_600)

        notifier.checkForCompletedNight()
        notificationManager.cancelAll()
        notifier.checkForCompletedNight()

        assertTrue("a second check must not re-post an already-notified night", shadowOf(notificationManager).allNotifications.isEmpty())
    }

    @Test
    fun `the setting turned off posts nothing and marks nothing notified`() = runTest {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val preference = SleepNotificationPreference(context).apply { enabled = false }
        val notifier = notifierAt(now = wokeAt + 3_600, preference = preference)

        notifier.checkForCompletedNight()

        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
        assertTrue("kept eligible for a later pass once the setting is back on", !db.notifiedSleepNights().isNotified(wokeAt))
    }

    @Test
    fun `a refused permission posts nothing, silently, even with the setting on`() = runTest {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifier = notifierAt(now = wokeAt + 3_600)

        notifier.checkForCompletedNight()

        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
        assertTrue(!db.notifiedSleepNights().isNotified(wokeAt))
    }

    @Test
    fun `a stale night -- several days later -- is never notified`() = runTest {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifier = notifierAt(now = wokeAt + 5 * 24 * 60 * 60)

        notifier.checkForCompletedNight()

        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
        assertTrue(!db.notifiedSleepNights().isNotified(wokeAt))
    }

    @Test
    fun `the posted notification uses its own channel, distinct from the candidate-detection one`() = runTest {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifierAt(now = wokeAt + 3_600).checkForCompletedNight()

        val channel = notificationManager.getNotificationChannel(SleepNightNotifier.CHANNEL_ID)
        assertTrue(channel != null)
        assertTrue(SleepNightNotifier.CHANNEL_ID != CandidateNotifier.CHANNEL_ID)
    }

    @Test
    fun `sendTestNotification posts through the same channel and requires only the runtime permission`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val preference = SleepNotificationPreference(context).apply { enabled = false }
        val deniedResult = SleepNightNotifier(context, db, preference, zurich) { 0L }.sendTestNotification()
        assertTrue(!deniedResult)

        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val grantedResult = SleepNightNotifier(context, db, preference, zurich) { 0L }.sendTestNotification()
        assertTrue("ignores the preference toggle, same as CandidateNotifier's own test action", grantedResult)
    }
}
