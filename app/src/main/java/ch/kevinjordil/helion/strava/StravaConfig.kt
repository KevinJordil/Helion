package ch.kevinjordil.helion.strava

import ch.kevinjordil.helion.BuildConfig

/**
 * Strava's developer app credentials, read from `local.properties` (`strava.clientId`,
 * `strava.clientSecret`) and injected as [BuildConfig] fields by `app/build.gradle.kts` --
 * never hard-coded, never committed. When either is missing from that file the build still
 * succeeds with empty strings, and [isConfigured] is what every caller checks before
 * attempting anything that needs them: direct upload (transport A) degrades to unavailable,
 * with the file-share transport (transport B) as the fallback, rather than the app
 * crashing or failing to compile.
 */
object StravaConfig {

    val clientId: String get() = BuildConfig.STRAVA_CLIENT_ID
    val clientSecret: String get() = BuildConfig.STRAVA_CLIENT_SECRET

    val isConfigured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    /** The redirect URI implemented on both ends: this constant and the manifest's intent-filter. */
    const val REDIRECT_URI: String = "helion://oauth-callback"

    /**
     * `activity:write` is required to upload; the account's existing tokens carry only
     * `read`, which cannot. `activity:write` implies read access too, so it is the only
     * scope requested -- there is no reason to also ask for `read` separately.
     */
    const val SCOPE: String = "activity:write"
}
