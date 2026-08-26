package ch.kevinjordil.helion.strava

import android.net.Uri
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONException
import org.json.JSONObject

/**
 * Thrown by [StravaAuth.validAccessToken] whenever the owner needs to go through the
 * browser authorisation flow again -- no refresh token stored yet, or Strava rejected the
 * one that was. [StravaPublisher] turns this into a plain "authorisation expired or
 * missing" message rather than a generic failure. [neverConnected] is what lets that
 * message be worded correctly: true when no authorisation has ever completed on this
 * device (a first run, or one that started fresh after a disconnect), false when one had
 * completed before and Strava has since revoked or expired it -- "reconnect" only makes
 * sense to say in the second case.
 */
class StravaAuthRequiredException(message: String, val neverConnected: Boolean) : Exception(message)

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
 * What the redirect intent from Strava's browser consent screen means, parsed from its
 * query parameters alone -- nothing here touches the network. Strava sends exactly one of
 * `code` (the owner approved) or `error` (the owner declined, or the app is misconfigured,
 * e.g. `access_denied` or `invalid_scope`); a redirect with neither is not a real OAuth
 * result and is ignored.
 */
sealed class OAuthRedirect {
    data class Code(val code: String) : OAuthRedirect()
    data class Error(val error: String) : OAuthRedirect()
    object NotAnOAuthRedirect : OAuthRedirect()
}

/** Parses [uri] -- an intent's `data` -- against [StravaConfig.REDIRECT_URI]'s scheme and host. */
fun parseOAuthRedirect(uri: Uri): OAuthRedirect {
    if (uri.scheme != "helion" || uri.host != "oauth-callback") return OAuthRedirect.NotAnOAuthRedirect
    uri.getQueryParameter("code")?.let { return OAuthRedirect.Code(it) }
    uri.getQueryParameter("error")?.let { return OAuthRedirect.Error(it) }
    return OAuthRedirect.NotAnOAuthRedirect
}

/**
 * Why the last OAuth attempt did not leave the owner connected, carrying Strava's own
 * explanation as [detail] wherever one exists -- the single most useful thing to show him,
 * since a bare category ("rejected") does not say *why* a client secret or a code was
 * rejected but Strava's own response text usually does. Never built from anything secret:
 * [detail] comes only from Strava's response body or a local exception message, never from
 * the client secret, access token or refresh token this app sends.
 */
sealed class StravaAuthFailure(val detail: String) {
    /** The owner declined Strava's consent screen, or Strava sent some other `error` value. */
    class Declined(detail: String) : StravaAuthFailure(detail)

    /** The token endpoint rejected the request outright -- an invalid client secret, a code already used, and the like. */
    class Rejected(detail: String) : StravaAuthFailure(detail)

    /** The request never reached Strava, or its response never came back. */
    class NetworkError(detail: String) : StravaAuthFailure(detail)

    /** This build has no client id/secret at all -- kept distinct so it is never confused with Strava actually rejecting something. */
    object NotConfigured : StravaAuthFailure("not configured")
}

/** Whether the account is connected right now, and why the last attempt fell short if it did. */
data class StravaAuthStatus(
    val connected: Boolean,
    val everConnected: Boolean,
    val lastFailure: StravaAuthFailure?,
)

/**
 * Drives the browser-based OAuth flow (building the authorize URL, exchanging the returned
 * code) and keeps the access token fresh afterwards. Never asks the owner to re-authorise
 * just because time passed: [validAccessToken] refreshes silently from [StravaTokenStore]'s
 * refresh token on every call where the current access token is missing or expiring soon.
 *
 * [status] is the one thing every screen that cares about the connection reads: a
 * [StateFlow] backed by [tokenStore], updated the instant an exchange, a refresh or a
 * disconnect changes anything, so a screen already on display (Réglages, the activity
 * detail screen) shows the new state immediately -- no re-navigation needed to force a
 * re-read, which is what let a successful reconnect look identical to a failed one before.
 */
class StravaAuth(
    private val tokenStore: StravaTokenStore,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : StravaAccessTokenProvider {

    private val _status = MutableStateFlow(readStatus())
    val status: StateFlow<StravaAuthStatus> = _status.asStateFlow()

    private fun readStatus() = StravaAuthStatus(
        connected = tokenStore.isAuthorized,
        everConnected = tokenStore.hasEverConnected,
        lastFailure = tokenStore.readLastAuthFailure(),
    )

    private fun refreshStatus() {
        _status.value = readStatus()
    }

    private fun recordFailure(failure: StravaAuthFailure) {
        tokenStore.writeLastAuthFailure(failure)
        refreshStatus()
    }

    private fun clearFailure() {
        tokenStore.writeLastAuthFailure(null)
        refreshStatus()
    }

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

    /**
     * Exchanges the authorization `code` from the redirect for tokens and stores them on
     * success. Never throws for a request that reached Strava and got an answer, even a
     * negative one -- that answer comes back as a [StravaAuthFailure] instead, so a caller
     * can never discard it by accident the way a swallowed exception did before.
     */
    fun exchangeCode(code: String): StravaAuthFailure? {
        if (!StravaConfig.isConfigured) return StravaAuthFailure.NotConfigured
        val body = try {
            postForm(
                "https://www.strava.com/oauth/token",
                mapOf(
                    "client_id" to StravaConfig.clientId,
                    "client_secret" to StravaConfig.clientSecret,
                    "code" to code,
                    "grant_type" to "authorization_code",
                ),
            )
        } catch (e: StravaHttpException) {
            return StravaAuthFailure.Rejected(describeStravaError(e))
        } catch (e: IOException) {
            return StravaAuthFailure.NetworkError(e.message ?: "network error")
        }
        return try {
            val json = JSONObject(body)
            tokenStore.save(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                expiresAt = json.getLong("expires_at"),
            )
            null
        } catch (e: JSONException) {
            StravaAuthFailure.Rejected(e.message ?: "unexpected response")
        }
    }

    /**
     * The single entry point [MainActivity][ch.kevinjordil.helion.MainActivity]'s redirect
     * handling calls: exchanges a code, or records that the owner declined, either way
     * updating [status]. Makes a network request for [OAuthRedirect.Code] -- call this off
     * the main thread.
     */
    fun handleRedirect(redirect: OAuthRedirect) {
        when (redirect) {
            is OAuthRedirect.Code -> {
                val failure = exchangeCode(redirect.code)
                if (failure != null) recordFailure(failure) else clearFailure()
            }
            is OAuthRedirect.Error -> recordFailure(StravaAuthFailure.Declined(redirect.error))
            OAuthRedirect.NotAnOAuthRedirect -> Unit
        }
    }

    /** Clears the stored tokens and any remembered failure -- Réglages' "se déconnecter" action. */
    fun disconnect() {
        tokenStore.clear()
        refreshStatus()
    }

    override suspend fun validAccessToken(): String {
        if (!StravaConfig.isConfigured) throw StravaNotConfiguredException()
        val refreshToken = tokenStore.refreshToken
            ?: throw StravaAuthRequiredException("no refresh token stored", neverConnected = !tokenStore.hasEverConnected)

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
                recordFailure(StravaAuthFailure.Rejected(describeStravaError(e)))
                throw StravaAuthRequiredException("refresh token rejected", neverConnected = false)
            }
            throw e
        }

        val json = JSONObject(body)
        val accessToken = json.getString("access_token")
        val newRefreshToken = json.optString("refresh_token", refreshToken)
        val expiresAt = json.getLong("expires_at")
        tokenStore.save(accessToken, newRefreshToken, expiresAt)
        clearFailure()
        return accessToken
    }

    private companion object {
        /** Renew a bit before actual expiry, so a slow upload never straddles the boundary. */
        const val EXPIRY_LEEWAY_SECONDS = 300L
    }
}

/** The stored-kind keys [StravaTokenStore.lastAuthFailureKind] holds, one per [StravaAuthFailure] subtype. */
private const val FAILURE_KIND_DECLINED = "declined"
private const val FAILURE_KIND_REJECTED = "rejected"
private const val FAILURE_KIND_NETWORK_ERROR = "network_error"
private const val FAILURE_KIND_NOT_CONFIGURED = "not_configured"

private fun StravaTokenStore.writeLastAuthFailure(failure: StravaAuthFailure?) {
    lastAuthFailureKind = when (failure) {
        null -> null
        is StravaAuthFailure.Declined -> FAILURE_KIND_DECLINED
        is StravaAuthFailure.Rejected -> FAILURE_KIND_REJECTED
        is StravaAuthFailure.NetworkError -> FAILURE_KIND_NETWORK_ERROR
        StravaAuthFailure.NotConfigured -> FAILURE_KIND_NOT_CONFIGURED
    }
    lastAuthFailureDetail = failure?.detail
}

private fun StravaTokenStore.readLastAuthFailure(): StravaAuthFailure? {
    val detail = lastAuthFailureDetail ?: return null
    return when (lastAuthFailureKind) {
        FAILURE_KIND_DECLINED -> StravaAuthFailure.Declined(detail)
        FAILURE_KIND_REJECTED -> StravaAuthFailure.Rejected(detail)
        FAILURE_KIND_NETWORK_ERROR -> StravaAuthFailure.NetworkError(detail)
        FAILURE_KIND_NOT_CONFIGURED -> StravaAuthFailure.NotConfigured
        else -> null
    }
}

/** Thrown by the raw HTTP helpers below on a non-2xx response. [body] is the response text, kept for [describeStravaError]. */
class StravaHttpException(val statusCode: Int, val body: String, message: String) : IOException(message)

/**
 * Strava's own explanation for a rejected request, e.g. `"Bad Request (Application: client_id invalid)"`
 * -- built only from Strava's JSON error body (`message` and the first `errors[]` entry's
 * `resource`/`field`/`code`, the documented shape of its OAuth error responses), never from
 * anything this app sent. Falls back to the bare HTTP status when the body is not that shape.
 */
internal fun describeStravaError(exception: StravaHttpException): String {
    val described = runCatching {
        val json = JSONObject(exception.body)
        val message = json.optString("message").takeIf { it.isNotBlank() }
        val firstError = json.optJSONArray("errors")?.optJSONObject(0)
        val fieldDetail = firstError?.let { err ->
            listOfNotNull(
                err.optString("resource").takeIf { it.isNotBlank() },
                err.optString("field").takeIf { it.isNotBlank() },
                err.optString("code").takeIf { it.isNotBlank() },
            ).joinToString(" ").takeIf { it.isNotBlank() }
        }
        listOfNotNull(message, fieldDetail?.let { "($it)" }).joinToString(" ")
    }.getOrNull()?.takeIf { it.isNotBlank() }
    return described ?: "HTTP ${exception.statusCode}"
}

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
        throw StravaHttpException(code, text, "Strava request failed with HTTP $code")
    }
    return text
}
