package ch.kevinjordil.helion.ui.settings

import android.content.Context

/**
 * The Réglages on/off switch for exporting to Health Connect -- see
 * [ch.kevinjordil.helion.healthconnect.HealthConnectExporter]'s own kdoc for what turning
 * this on actually asks for and writes. Off means nothing is ever written: every export path
 * (the one after an ingest pass, and the manual "Exporter maintenant" button in Réglages)
 * checks this first and returns immediately when it is false, the same "off means off,
 * everywhere" contract [NotificationPreference] and [CustomServerConfig] already keep for
 * their own features.
 *
 * Same "helion" preferences file every other Réglages toggle already uses. Defaults to
 * disabled, unlike [NotificationPreference]: writing the owner's own health data into
 * another app's shared store is exactly the kind of thing that must be opted into, never a
 * silent default an update turns on for an owner who never asked for it.
 */
class HealthConnectConfig(context: Context) {

    private val prefs = context.getSharedPreferences("helion", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    companion object {
        private const val KEY_ENABLED = "health_connect_enabled"
    }
}
