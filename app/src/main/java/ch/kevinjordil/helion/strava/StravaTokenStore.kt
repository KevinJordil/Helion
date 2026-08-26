package ch.kevinjordil.helion.strava

import android.content.Context

/**
 * Persists the Strava OAuth tokens across process restarts, in their own SharedPreferences
 * file (not the app's general "helion" one, purely to keep this sensitive material in one
 * clearly-named place). Never logged: nothing in this class or its callers writes a token
 * to Logcat, only whether one is present.
 *
 * [refreshToken] is the durable credential -- access tokens expire in hours (see
 * [StravaAuth.validAccessToken]) and are refreshed automatically from it, so a user is never
 * asked to re-authorise just because time passed. It becomes null again only when Strava
 * itself rejects it (revoked authorisation, or the scope was never `activity:write`), which
 * [StravaAuth] surfaces as [StravaAuthRequiredException].
 */
class StravaTokenStore(context: Context) {

    private val prefs = context.getSharedPreferences("strava_oauth", Context.MODE_PRIVATE)

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    /** Unix seconds at which [accessToken] stops being valid; 0 if never set. */
    var expiresAt: Long
        get() = prefs.getLong(KEY_EXPIRES_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_EXPIRES_AT, value).apply()

    /** Whether an authorisation was ever completed -- the durable refresh token exists. */
    val isAuthorized: Boolean get() = !refreshToken.isNullOrBlank()

    /**
     * Whether an authorisation has ever completed on this device, even if [clear] has since
     * wiped the tokens themselves. This is what lets the "never connected" and "expired"
     * messages read differently -- unlike [isAuthorized] it survives [clear], since the
     * whole point of it is telling the two situations apart after the tokens are gone.
     */
    var hasEverConnected: Boolean
        get() = prefs.getBoolean(KEY_EVER_CONNECTED, false)
        set(value) = prefs.edit().putBoolean(KEY_EVER_CONNECTED, value).apply()

    /** The machine-readable kind of [ch.kevinjordil.helion.strava.StravaAuthFailure] last recorded, or null if none / cleared. */
    var lastAuthFailureKind: String?
        get() = prefs.getString(KEY_LAST_AUTH_FAILURE_KIND, null)
        set(value) = prefs.edit().putString(KEY_LAST_AUTH_FAILURE_KIND, value).apply()

    /** Strava's own explanation text for [lastAuthFailureKind], never the client secret or a token. */
    var lastAuthFailureDetail: String?
        get() = prefs.getString(KEY_LAST_AUTH_FAILURE_DETAIL, null)
        set(value) = prefs.edit().putString(KEY_LAST_AUTH_FAILURE_DETAIL, value).apply()

    fun save(accessToken: String, refreshToken: String, expiresAt: Long) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        this.expiresAt = expiresAt
        this.hasEverConnected = true
    }

    /**
     * Clears the tokens and any remembered failure -- called both when Strava reports the
     * refresh token itself is no longer valid, and when the owner disconnects by hand from
     * Réglages. [hasEverConnected] is deliberately preserved across this: it records history,
     * not current connection state.
     */
    fun clear() {
        val everConnected = hasEverConnected
        prefs.edit().clear().apply()
        if (everConnected) hasEverConnected = true
    }

    private companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_EVER_CONNECTED = "has_ever_connected"
        const val KEY_LAST_AUTH_FAILURE_KIND = "last_auth_failure_kind"
        const val KEY_LAST_AUTH_FAILURE_DETAIL = "last_auth_failure_detail"
    }
}
