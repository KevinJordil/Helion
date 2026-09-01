package ch.kevinjordil.helion.customserver

import ch.kevinjordil.helion.store.ActivityDao
import ch.kevinjordil.helion.store.MinuteSampleDao
import ch.kevinjordil.helion.store.Publication
import ch.kevinjordil.helion.store.PublicationDao
import ch.kevinjordil.helion.store.PublicationState
import ch.kevinjordil.helion.store.PublicationTarget
import ch.kevinjordil.helion.export.activityDisplayName
import ch.kevinjordil.helion.export.calorieEstimateFor
import ch.kevinjordil.helion.export.externalIdFor
import ch.kevinjordil.helion.export.tcxDownloadFileName
import ch.kevinjordil.helion.export.writeTcx
import ch.kevinjordil.helion.ui.settings.CustomServerConfig
import ch.kevinjordil.helion.ui.settings.Profile
import java.net.HttpURLConnection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Machine-readable failure reasons stored in [Publication.lastError], mapped to French in the UI. */
object CustomServerFailureReason {
    /** No URL, no token, or neither -- [CustomServerConfig.isConfigured] is false. */
    const val NOT_CONFIGURED = "custom_server_not_configured"

    /** [CustomServerConfig.serverUrl] does not parse as a usable `http(s)://` URL. */
    const val INVALID_URL = "custom_server_invalid_url"

    /**
     * The configured URL is plain `http://` and [CustomServerConfig.allowPlainHttp] has not
     * been explicitly turned on in Réglages -- see that flag's own kdoc. Never a silent
     * default: an activity is health data, and this is the one deliberate confirmation
     * standing between it and clear text over the network.
     */
    const val PLAIN_HTTP_NOT_CONFIRMED = "custom_server_plain_http_not_confirmed"

    /** The request never reached the server, or its response never came back -- a genuine transport failure (e.g. an unreachable host). */
    const val NETWORK_ERROR = "custom_server_network_error"

    /** The server answered 401 -- almost always the configured token being wrong or revoked. */
    const val UNAUTHORIZED = "custom_server_unauthorized"

    /** The server answered with any other non-2xx status. [Publication.lastErrorDetail] carries its own body. */
    const val REMOTE_ERROR = "custom_server_remote_error"

    /**
     * [ch.kevinjordil.helion.store.Activity.sport] is null -- see that field's own kdoc.
     * Checked before anything else, even [NOT_CONFIGURED]: sending an unset sport under a
     * generic label would silently mislabel the activity on the receiving end forever, so
     * this is refused the same way every other export path refuses it, never a guessed or
     * generic value sent in its place.
     */
    const val NO_SPORT = "custom_server_no_sport"
}

private val START_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

/**
 * How much of a response body is kept as [Publication.lastErrorDetail] or
 * [Publication.lastMessage] -- generous for a real diagnosis, short of pasting an entire
 * HTML error page into the UI. Shared by both, since a success and a failure response come
 * from the same server and are sanitized by the same [sanitizeServerMessage].
 */
private const val MAX_SERVER_MESSAGE_LENGTH = 500

/** Appended to a server message trimmed by [sanitizeServerMessage], so a capped message never reads as complete when it is not. */
private const val TRUNCATION_MARKER = "…"

/**
 * Cleans up [raw] -- arbitrary text from the owner's own server, rendered straight into the
 * UI -- before it is stored or shown anywhere: trims surrounding whitespace, strips control
 * characters that could break layout (anything other than a plain newline or tab), and caps
 * the result at [MAX_SERVER_MESSAGE_LENGTH] so a stray HTML error page cannot flood the
 * screen. Returns null for a body that has nothing left to show once cleaned -- an empty
 * response, or one that was only whitespace or control bytes -- so the caller can fall back
 * to its own wording instead of showing a blank line.
 */
internal fun sanitizeServerMessage(raw: String): String? {
    val withoutControlChars = raw.filter { it == '\n' || it == '\t' || !it.isISOControl() }
    val cleaned = withoutControlChars.trim()
    if (cleaned.isEmpty()) return null
    return if (cleaned.length > MAX_SERVER_MESSAGE_LENGTH) {
        cleaned.take(MAX_SERVER_MESSAGE_LENGTH) + TRUNCATION_MARKER
    } else {
        cleaned
    }
}

/**
 * "HTTP $statusCode: $message" when [rawBody] sanitizes to something real, or just
 * "HTTP $statusCode" when it does not (an empty body, or one that reads as nothing once
 * cleaned) -- the owner still sees the exact status either way, never a blank detail.
 */
internal fun formatServerDetail(statusCode: Int, rawBody: String): String {
    val message = sanitizeServerMessage(rawBody)
    return if (message != null) "HTTP $statusCode: $message" else "HTTP $statusCode"
}

/**
 * Sends one [ch.kevinjordil.helion.store.Activity] to the owner's own server as one
 * synchronous `multipart/form-data` POST -- see `README.md` for the exact request shape.
 *
 * There is no asynchronous upload job to resume here: the request either lands in one call
 * or it does not, so this reuses [ch.kevinjordil.helion.store.Publication] and
 * [PublicationState] purely as a *record* of the last attempt (state, timestamp, and either
 * a failure reason and detail or a success message) rather than as a resumable workflow --
 * [PublicationState.UPLOADING]
 * and [Publication.uploadId]/[Publication.remoteId] are simply never used for
 * [PublicationTarget.CUSTOM_SERVER]: this transport has no asynchronous job id and no
 * server-assigned resource id to remember. A second send of the same activity is still
 * safe to repeat: [ch.kevinjordil.helion.export.externalIdFor] gives the receiving server
 * the same stable id every time, which is what lets it recognise a repeat rather than
 * create a duplicate (see the `external_id` field in `README.md`).
 */
class CustomServerPublisher(
    private val activities: ActivityDao,
    private val minuteSamples: MinuteSampleDao,
    private val publications: PublicationDao,
    private val config: CustomServerConfig,
    private val api: CustomServerApi,
    // Optional so wiring/tests with no profile to give still work: no profile simply means
    // no calorie figure is sent, never a guessed one.
    private val profile: Profile? = null,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    sealed class Result {
        /**
         * [alreadyKnown] mirrors the row's stored [PublicationState]: true for a `200`
         * (the server already had this activity and sent nothing on to Strava again),
         * false for a `202` (freshly accepted). [message] is the server's own sanitized
         * response text (see [sanitizeServerMessage]), or null when it had none to show.
         */
        data class Sent(val alreadyKnown: Boolean, val statusCode: Int, val message: String?) : Result()
        data class Failed(val reason: String) : Result()
    }

    suspend fun send(activityId: Long): Result {
        val activity = activities.get(activityId)
            ?: return Result.Failed(CustomServerFailureReason.REMOTE_ERROR)

        val sport = activity.sport
        if (sport == null) {
            recordFailure(activityId, CustomServerFailureReason.NO_SPORT)
            return Result.Failed(CustomServerFailureReason.NO_SPORT)
        }

        val serverUrl = config.serverUrl
        val token = config.token
        if (!config.isConfigured || serverUrl == null || token == null) {
            recordFailure(activityId, CustomServerFailureReason.NOT_CONFIGURED)
            return Result.Failed(CustomServerFailureReason.NOT_CONFIGURED)
        }

        when (val validation = validateCustomServerUrl(serverUrl)) {
            is CustomServerUrlValidation.Blank, CustomServerUrlValidation.Malformed -> {
                recordFailure(activityId, CustomServerFailureReason.INVALID_URL)
                return Result.Failed(CustomServerFailureReason.INVALID_URL)
            }
            is CustomServerUrlValidation.Valid -> {
                if (validation.isPlainHttp && !config.allowPlainHttp) {
                    recordFailure(activityId, CustomServerFailureReason.PLAIN_HTTP_NOT_CONFIRMED)
                    return Result.Failed(CustomServerFailureReason.PLAIN_HTTP_NOT_CONFIRMED)
                }
            }
        }

        val samples = minuteSamples.between(activity.startTimestamp, activity.endTimestamp)
        val calories = calorieEstimateFor(profile, activity, zone, samples)
        val tcx = writeTcx(sport, activity.startTimestamp, activity.endTimestamp, samples, calories)
        val request = CustomServerSendRequest(
            tcx = tcx,
            fileName = tcxDownloadFileName(sport, activity.startTimestamp, zone),
            sport = customServerSportSlug(sport),
            title = activityDisplayName(activity),
            // The owner's own words only -- never [Activity.detectionContext], which is
            // diagnostic text for reviewing a candidate, not something to publish.
            description = activity.notes.orEmpty(),
            startIso = START_TIME_FORMAT.format(Instant.ofEpochSecond(activity.startTimestamp).atZone(zone)),
            durationSeconds = (activity.endTimestamp - activity.startTimestamp).coerceAtLeast(0),
            calories = calories,
            externalId = externalIdFor(activityId),
        )

        return try {
            val response = api.send(serverUrl, token, request)
            // Any 2xx is a success. The server uses 200 for "already received, nothing
            // re-sent to Strava" and 202 for "freshly accepted" -- see
            // [ch.kevinjordil.helion.store.PublicationState.ALREADY_KNOWN]'s own kdoc for
            // why that distinction is worth keeping, not just collapsing to "sent".
            val alreadyKnown = response.statusCode == HttpURLConnection.HTTP_OK
            val message = sanitizeServerMessage(response.body)
            markSent(activityId, alreadyKnown, response.statusCode, message)
            Result.Sent(alreadyKnown, response.statusCode, message)
        } catch (e: Exception) {
            val (reason, detail) = classifyFailure(e)
            recordFailure(activityId, reason, detail)
            Result.Failed(reason)
        }
    }

    /**
     * [reason, detail] for an exception from [api]'s call. A [CustomServerHttpException] is
     * a genuine answer from the owner's own server -- its status and body say why -- so it
     * becomes [CustomServerFailureReason.UNAUTHORIZED] (401, the configured token rejected)
     * or [CustomServerFailureReason.REMOTE_ERROR] (any other non-2xx, with the server's own
     * body as the detail, never the token). Anything else -- no connectivity, a timeout, an
     * unreachable host -- never reached the server at all and is
     * [CustomServerFailureReason.NETWORK_ERROR]; its detail is the local exception's own
     * message, which never contains the token since it never flows through an
     * [java.io.IOException] message.
     */
    private fun classifyFailure(e: Exception): Pair<String, String?> = when (e) {
        is CustomServerHttpException -> {
            val reason = if (e.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                CustomServerFailureReason.UNAUTHORIZED
            } else {
                CustomServerFailureReason.REMOTE_ERROR
            }
            reason to formatServerDetail(e.statusCode, e.body)
        }
        else -> CustomServerFailureReason.NETWORK_ERROR to (e.message ?: "network error")
    }

    private suspend fun markSent(activityId: Long, alreadyKnown: Boolean, statusCode: Int, message: String?) {
        publications.upsert(
            Publication(
                activityId = activityId,
                target = PublicationTarget.CUSTOM_SERVER,
                remoteId = null,
                uploadId = null,
                state = if (alreadyKnown) PublicationState.ALREADY_KNOWN else PublicationState.PUBLISHED,
                lastAttempt = now(),
                lastError = null,
                lastErrorDetail = null,
                // Null when there was nothing real to show (an empty body, or one that
                // sanitized to nothing) -- the UI then shows only the plain state label,
                // never a blank message line.
                lastMessage = message?.let { "HTTP $statusCode: $it" },
            ),
        )
    }

    private suspend fun recordFailure(activityId: Long, reason: String, detail: String? = null) {
        val existing = publications.get(activityId, PublicationTarget.CUSTOM_SERVER)
        val row = existing?.copy(
            state = PublicationState.FAILED,
            lastAttempt = now(),
            lastError = reason,
            lastErrorDetail = detail,
            // Clears out a previous success's message: this attempt failed, so nothing
            // from an earlier send should linger and be shown alongside it.
            lastMessage = null,
        ) ?: Publication(
            activityId = activityId,
            target = PublicationTarget.CUSTOM_SERVER,
            remoteId = null,
            uploadId = null,
            state = PublicationState.FAILED,
            lastAttempt = now(),
            lastError = reason,
            lastErrorDetail = detail,
        )
        publications.upsert(row)
    }
}
