package ch.kevinjordil.helion.customserver

import java.io.IOException

/**
 * Everything one send needs, already resolved to plain values -- no [ch.kevinjordil.helion.store.Activity]
 * or [ch.kevinjordil.helion.store.MinuteSample] reaches [CustomServerApi] itself, so it can
 * be exercised in a test with no database and no network. See `README.md` for the exact
 * request shape this is turned into.
 */
data class CustomServerSendRequest(
    val tcx: String,
    val fileName: String,
    val sport: String,
    val title: String,
    val description: String,
    val startIso: String,
    val durationSeconds: Long,
    /** Omitted from the request entirely when null -- see the `calories` field's own kdoc in `README.md`. */
    val calories: Int?,
    val externalId: String,
)

/**
 * The one call [CustomServerPublisher] needs. Kept as an interface so tests can script a
 * non-2xx response or a transport failure without a real network call; [HttpCustomServerApi]
 * is the only implementation used outside tests.
 */
interface CustomServerApi {

    /**
     * POSTs [request] as `multipart/form-data` to [serverUrl] with `Authorization: Bearer
     * $token`. Throws [CustomServerHttpException] on a non-2xx response, or a plain
     * [IOException] (unreachable host, timeout, reset connection, ...) when the request
     * never got a response at all.
     */
    fun send(serverUrl: String, token: String, request: CustomServerSendRequest)
}

/**
 * Thrown by [HttpCustomServerApi.send] on a non-2xx response. [body] is the response text
 * as sent by the owner's own server, kept verbatim so [CustomServerPublisher] can show it --
 * this project has been bitten before by a swallowed cause, see [CustomServerPublisher]'s
 * own kdoc.
 */
class CustomServerHttpException(val statusCode: Int, val body: String) :
    IOException("Custom server request failed with HTTP $statusCode")
