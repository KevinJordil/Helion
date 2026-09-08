package ch.kevinjordil.helion.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the actual cause behind the owner never receiving a candidate notification -- and the
 * two further ways a notification can fail to arrive that turned out to sit right next to
 * it, each just as silent: [NotificationPreference.enabled] defaults to `true`, so
 * Réglages' notifications checkbox rendered already checked on first visit, and the runtime
 * `POST_NOTIFICATIONS` permission it alone used to gate on was therefore never requested.
 * Fixing that exposed two siblings -- the whole app disabled in system settings, and the
 * candidate-detection channel itself disabled -- that the original single check never even
 * looked at.
 *
 * [notificationDiagnosticsState] is what replaced the old, single-condition
 * `notificationPermissionMissingWarning`: it must produce its own distinct state for each of
 * the three failing cases, [NotificationDiagnosticsState.ALL_CLEAR] when every check passes,
 * and `null` -- nothing to diagnose at all -- when the owner has the toggle off, mirroring
 * [ch.kevinjordil.helion.notification.CandidateNotifier]'s own gate.
 */
class NotificationsSettingsSectionTest {

    @Test
    fun `the default state -- preference on, permission never requested -- asks for the runtime permission`() {
        // Mirrors a fresh install exactly: NotificationPreference.enabled defaults to true,
        // and Android's POST_NOTIFICATIONS permission has never been requested at all, so
        // it is still askable.
        assertEquals(
            NotificationDiagnosticsState.PERMISSION_MISSING,
            notificationDiagnosticsState(
                preferenceEnabled = true,
                permissionGranted = false,
                canRequestPermission = true,
                appNotificationsEnabled = true,
                channelBlocksNotifications = false,
            ),
        )
    }

    @Test
    fun `a permanently denied permission points at the app's own settings, not the runtime prompt`() {
        assertEquals(
            NotificationDiagnosticsState.PERMISSION_DENIED,
            notificationDiagnosticsState(
                preferenceEnabled = true,
                permissionGranted = false,
                canRequestPermission = false,
                appNotificationsEnabled = true,
                channelBlocksNotifications = false,
            ),
        )
    }

    @Test
    fun `the permission granted but the whole app disabled in system settings is its own distinct state`() {
        assertEquals(
            NotificationDiagnosticsState.APP_DISABLED,
            notificationDiagnosticsState(
                preferenceEnabled = true,
                permissionGranted = true,
                canRequestPermission = false,
                appNotificationsEnabled = false,
                channelBlocksNotifications = false,
            ),
        )
    }

    @Test
    fun `permission granted, app allowed, but the channel itself disabled is its own distinct state`() {
        assertEquals(
            NotificationDiagnosticsState.CHANNEL_DISABLED,
            notificationDiagnosticsState(
                preferenceEnabled = true,
                permissionGranted = true,
                canRequestPermission = false,
                appNotificationsEnabled = true,
                channelBlocksNotifications = true,
            ),
        )
    }

    @Test
    fun `all three checks passing is reported plainly, not left as the mere absence of a warning`() {
        assertEquals(
            NotificationDiagnosticsState.ALL_CLEAR,
            notificationDiagnosticsState(
                preferenceEnabled = true,
                permissionGranted = true,
                canRequestPermission = false,
                appNotificationsEnabled = true,
                channelBlocksNotifications = false,
            ),
        )
    }

    @Test
    fun `turning the setting off means nothing to diagnose, whatever the platform state is`() {
        // Matches CandidateNotifier's own degrade-silently rule: an owner who chose not to
        // be notified is not shown a diagnosis of a path he deliberately is not using.
        assertNull(
            notificationDiagnosticsState(
                preferenceEnabled = false,
                permissionGranted = false,
                canRequestPermission = true,
                appNotificationsEnabled = false,
                channelBlocksNotifications = true,
            ),
        )
        assertNull(
            notificationDiagnosticsState(
                preferenceEnabled = false,
                permissionGranted = true,
                canRequestPermission = false,
                appNotificationsEnabled = true,
                channelBlocksNotifications = false,
            ),
        )
    }
}
