package ch.kevinjordil.helion.ui.settings

import android.content.Context

/**
 * The free-text name written into the TCX `<Creator><Name>` element (see
 * [ch.kevinjordil.helion.export.writeTcx]) -- what Strava shows as the device an activity
 * was recorded with. Strava simply matches this text against its own device list and shows
 * it verbatim otherwise, so any string the owner types is usable.
 *
 * Defaults to [DEFAULT_DEVICE_NAME], the strap's own name, so a fresh install already shows
 * something sensible on Strava without the owner having to type anything -- but that default
 * is only a starting value, not a floor: once the owner clears the field it stays empty
 * (rather than snapping back to the default), which is exactly what tells [writeTcx] to omit
 * `<Creator>` entirely instead of writing one with an empty name.
 *
 * Same "helion" preferences file [StepsGoal] and the rest of Réglages' small values already
 * use, under its own key.
 */
class RecordingDeviceName(context: Context) {

    private val prefs = context.getSharedPreferences("helion", Context.MODE_PRIVATE)

    var value: String
        get() = prefs.getString(KEY_DEVICE_NAME, null) ?: DEFAULT_DEVICE_NAME
        set(name) = prefs.edit().putString(KEY_DEVICE_NAME, name).apply()

    companion object {
        const val DEFAULT_DEVICE_NAME = "Amazfit Helio Strap"
        private const val KEY_DEVICE_NAME = "recording_device_name"
    }
}
