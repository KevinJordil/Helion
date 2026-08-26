package ch.kevinjordil.helion.ui.settings

import android.content.Context

/**
 * The owner's own always-on server: where activities can be sent as a `multipart/form-data`
 * POST (see [ch.kevinjordil.helion.customserver.CustomServerPublisher] and `README.md`'s
 * custom-server contract), and the shared token that authenticates that request.
 *
 * Same "helion" preferences file [Profile] and [StepsGoal] already use, under their own
 * keys -- see [Profile]'s own kdoc for why a plain [android.content.SharedPreferences] file
 * is enough here: this stays on the device until the owner explicitly taps send.
 *
 * [allowPlainHttp] is a one-time, explicit acknowledgement that a plain-`http://` URL sends
 * health data in clear text over the network -- see
 * [ch.kevinjordil.helion.customserver.validateCustomServerUrl] for where this is enforced.
 * It defaults to false and is never inferred from the URL itself: switching the URL back to
 * `https://` never silently carries a stale acknowledgement forward in a way that matters,
 * since [ch.kevinjordil.helion.customserver.CustomServerPublisher] only ever consults this
 * flag when the configured URL is actually plain HTTP.
 */
class CustomServerConfig(context: Context) {

    private val prefs = context.getSharedPreferences("helion", Context.MODE_PRIVATE)

    var serverUrl: String?
        get() = prefs.getString(KEY_URL, null)
        set(value) {
            val edit = prefs.edit()
            if (value.isNullOrBlank()) edit.remove(KEY_URL) else edit.putString(KEY_URL, value)
            edit.apply()
        }

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) {
            val edit = prefs.edit()
            if (value.isNullOrBlank()) edit.remove(KEY_TOKEN) else edit.putString(KEY_TOKEN, value)
            edit.apply()
        }

    var allowPlainHttp: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_PLAIN_HTTP, false)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_PLAIN_HTTP, value).apply()

    /** True once both a URL and a token are set -- neither alone is enough to attempt a send. */
    val isConfigured: Boolean
        get() = !serverUrl.isNullOrBlank() && !token.isNullOrBlank()

    companion object {
        private const val KEY_URL = "custom_server_url"
        private const val KEY_TOKEN = "custom_server_token"
        private const val KEY_ALLOW_PLAIN_HTTP = "custom_server_allow_plain_http"
    }
}
