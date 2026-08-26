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

    companion object {
        private const val KEY_ENABLED = "notifications_enabled"
    }
}
