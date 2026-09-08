package ch.kevinjordil.helion.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the actual cause behind the owner never receiving a candidate notification:
 * [NotificationPreference.enabled] defaults to `true`, so Réglages' notifications checkbox
 * rendered already checked on first visit -- its `onCheckedChange` (the only place the
 * runtime permission used to be requested) therefore never fired, `POST_NOTIFICATIONS` was
 * never granted, and nothing on screen ever said so. [notificationPermissionMissingWarning]
 * is the fix: it must be true in exactly that "enabled by default, permission never touched"
 * starting state, which the checkbox toggle path alone could not detect.
 */
class NotificationsSettingsSectionTest {

    @Test
    fun `the default state -- preference on, permission never granted -- shows the warning`() {
        // Mirrors a fresh install exactly: NotificationPreference.enabled defaults to true,
        // and Android's POST_NOTIFICATIONS permission has never been requested at all.
        assertTrue(notificationPermissionMissingWarning(preferenceEnabled = true, permissionGranted = false))
    }

    @Test
    fun `once the permission is actually granted, the warning goes away`() {
        assertFalse(notificationPermissionMissingWarning(preferenceEnabled = true, permissionGranted = true))
    }

    @Test
    fun `turning the setting off never shows the warning, permission or not`() {
        // Matches CandidateNotifier's own degrade-silently rule: an owner who chose not to
        // be notified is not nagged about a permission that setting makes moot.
        assertFalse(notificationPermissionMissingWarning(preferenceEnabled = false, permissionGranted = false))
        assertFalse(notificationPermissionMissingWarning(preferenceEnabled = false, permissionGranted = true))
    }
}
