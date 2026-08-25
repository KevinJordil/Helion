package ch.kevinjordil.helion.strava

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Thrown by [StravaAuth.validAccessToken] whenever the owner needs to go through the
 * browser authorisation flow again -- no refresh token stored yet, or Strava rejected the
 * one that was. [StravaPublisher] turns this into a plain "authorisation expired or
 * missing" message rather than a generic failure, since that is the one the owner will
 * actually hit once his Standard-tier subscription lapses.
 */
class StravaAuthRequiredException(message: String) : Exception(message)

/** Thrown when `local.properties` has no `strava.clientId`/`strava.clientSecret`. */
class StravaNotConfiguredException : Exception("Strava client id/secret not configured")

/** What [StravaPublisher] needs from the auth layer: a currently-valid access token. */
interface StravaAccessTokenProvider {
    /**
     * A valid access token, refreshing from the stored refresh token first if the current
     * one is missing or close to expiry. Throws [StravaAuthRequiredException] if there is
     * no refresh token to renew from, or Strava reports it is no longer valid, and
     * [StravaNotConfiguredException] if the app has no client credentials at all.
     */
    suspend fun validAccessToken(): String
}

/**
 * Drives the browser-based OAuth flow (building the authorize URL, exchanging the returned
 * code) and keeps the access token fresh afterwards. Never asks the owner to re-authorise
 * just because time passed: [validAccessToken] refreshes silently from [StravaTokenStore]'s
 * refresh token on every call where the current access token is missing or expiring soon.
 */
class StravaAuth(
    private val tokenStore: StravaTokenStore,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : StravaAccessTokenProvider {

    /**
     * The browser/Custom Tab URL to open for authorisation. Requests exactly
     * [StravaConfig.SCOPE] (`activity:write`) -- the existing `read`-scoped tokens on the
     * account cannot upload, so this is not additive to them, it replaces them once the
     * owner completes this flow.
     */
    fun authorizeUrl(): String {
        check(StravaConfig.isConfigured) { "Strava client id/secret not configured" }
        return "https://www.strava.com/oauth/mobile/authorize" +
            "?client_id=${urlEncode(StravaConfig.clientId)}" +
            "&redirect_uri=${urlEncode(StravaConfig.REDIRECT_URI)}" +
            "&response_type=code" +
            "&approval_prompt=auto" +
            "&scope=${urlEncode(StravaConfig.SCOPE)}"
    }

    /** Exchanges the authorization `code` from the redirect for tokens and stores them. */
    fun exchangeCode(code: String) {
        if (!StravaConfig.isConfigured) throw StravaNotConfiguredException()
        val body = postForm(
            "https://www.strava.com/oauth/token",
            mapOf(
                "client_id" to StravaConfig.clientId,
                "client_secret" to StravaConfig.clientSecret,
                "code" to code,
                "grant_type" to "authorization_code",
            ),
        )
        val json = JSONObject(body)
        tokenStore.save(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            expiresAt = json.getLong("expires_at"),
        )
    }

    override suspend fun validAccessToken(): String {
        if (!StravaConfig.isConfigured) throw StravaNotConfiguredException()
        val refreshToken = tokenStore.refreshToken ?: throw StravaAuthRequiredException("no refresh token stored")

        val current = tokenStore.accessToken
        if (current != null && tokenStore.expiresAt > now() + EXPIRY_LEEWAY_SECONDS) {
            return current
        }

        val body = try {
            postForm(
                "https://www.strava.com/oauth/token",
                mapOf(
                    "client_id" to StravaConfig.clientId,
                    "client_secret" to StravaConfig.clientSecret,
                    "refresh_token" to refreshToken,
                    "grant_type" to "refresh_token",
                ),
            )
        } catch (e: StravaHttpException) {
            if (e.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED || e.statusCode == HttpURLConnection.HTTP_BAD_REQUEST) {
                tokenStore.clear()
                throw StravaAuthRequiredException("refresh token rejected")
            }
            throw e
        }

        val json = JSONObject(body)
        val accessToken = json.getString("access_token")
        val newRefreshToken = json.optString("refresh_token", refreshToken)
        val expiresAt = json.getLong("expires_at")
        tokenStore.save(accessToken, newRefreshToken, expiresAt)
        return accessToken
    }

    private companion object {
        /** Renew a bit before actual expiry, so a slow upload never straddles the boundary. */
        const val EXPIRY_LEEWAY_SECONDS = 300L
    }
}

/** Thrown by the raw HTTP helpers below on a non-2xx response. */
class StravaHttpException(val statusCode: Int, message: String) : IOException(message)

internal fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

/** A plain `application/x-www-form-urlencoded` POST, returning the response body as text. */
internal fun postForm(urlString: String, params: Map<String, String>): String {
    val body = params.entries.joinToString("&") { (key, value) -> "${urlEncode(key)}=${urlEncode(value)}" }
    val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connectTimeout = 15_000
        readTimeout = 30_000
    }
    return try {
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        readResponse(connection)
    } finally {
        connection.disconnect()
    }
}

internal fun readResponse(connection: HttpURLConnection): String {
    val code = connection.responseCode
    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
    val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    if (code !in 200..299) {
        throw StravaHttpException(code, "Strava request failed with HTTP $code")
    }
    return text
}
