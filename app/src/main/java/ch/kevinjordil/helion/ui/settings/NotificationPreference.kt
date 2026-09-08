package ch.kevinjordil.helion.ui.settings

import android.content.Context

/**
 * Whether the owner wants candidate-detection notifications at all -- the Réglages toggle
 * next to `notification_settings_section_title`. Turning this off must only ever gate
 * [ch.kevinjordil.helion.notification.CandidateNotifier]'s own call to
 * `NotificationManager.notify`: detection keeps running and candidates keep appearing in
 * Activités either way, exactly as silently as when Android's own runtime permission is
 * refused instead (see [ch.kevinjordil.helion.source.CandidateNotificationSink]'s kdoc).
 *
 * Same "helion" preferences file [StepsGoal] and [Profile] already use, under its own key.
 * Defaults to enabled: an owner who has not touched this setting yet is exactly the one who
 * benefits from being asked for the notification permission in the first place.
 */
class NotificationPreference(context: Context) {

    private val prefs = context.getSharedPreferences("helion", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /**
     * Whether `POST_NOTIFICATIONS` has ever actually been requested from this install --
     * the one bit Android itself does not expose. `ActivityCompat.shouldShowRequestPermissionRationale`
     * answers `false` both before the permission has ever been asked for and after it has
     * been permanently denied ("don't ask again"); those two are told apart only by
     * remembering, ourselves, whether a request was ever launched. See
     * [ch.kevinjordil.helion.ui.settings.NotificationsSettingsSection]'s own kdoc for where
     * this is used to route the owner to the runtime prompt versus the app's own settings.
     */
    var permissionRequested: Boolean
        get() = prefs.getBoolean(KEY_PERMISSION_REQUESTED, false)
        set(value) = prefs.edit().putBoolean(KEY_PERMISSION_REQUESTED, value).apply()

    companion object {
        private const val KEY_ENABLED = "notifications_enabled"
        private const val KEY_PERMISSION_REQUESTED = "notifications_permission_requested"
    }
}
