package ch.kevinjordil.helion.ui.settings

import android.content.Context

/**
 * Whether the owner wants the morning sleep-summary notification at all -- Réglages' own
 * toggle for that channel, deliberately separate from [NotificationPreference.enabled] so
 * he can silence one of the two without touching the other (see
 * [ch.kevinjordil.helion.notification.SleepNightNotifier]'s own kdoc). Turning this off
 * only ever gates that one call to `NotificationManager.notify`: Sommeil itself, and the
 * averages panel, keep working exactly the same either way.
 *
 * Same "helion" preferences file every other Réglages setting already uses, under its own
 * key. Defaults to enabled, matching [NotificationPreference.enabled]'s own default and
 * for the same reason: an owner who has not touched this setting yet is exactly the one
 * who benefits from being asked for the notification permission the first time either
 * channel needs it. The runtime `POST_NOTIFICATIONS` permission itself is shared across
 * every channel this app has -- Android grants or denies it once for the whole app, not
 * per channel -- so whether it has ever been requested is tracked once, on
 * [NotificationPreference.permissionRequested], not duplicated here.
 */
class SleepNotificationPreference(context: Context) {

    private val prefs = context.getSharedPreferences("helion", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    companion object {
        private const val KEY_ENABLED = "sleep_notifications_enabled"
    }
}
