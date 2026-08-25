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

    fun save(accessToken: String, refreshToken: String, expiresAt: Long) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        this.expiresAt = expiresAt
    }

    /** Clears everything -- called when Strava reports the refresh token itself is no longer valid. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at"
    }
}
