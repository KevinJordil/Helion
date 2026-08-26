package ch.kevinjordil.helion.customserver

import ch.kevinjordil.helion.store.ActivityDao
import ch.kevinjordil.helion.store.MinuteSampleDao
import ch.kevinjordil.helion.store.Publication
import ch.kevinjordil.helion.store.PublicationDao
import ch.kevinjordil.helion.store.PublicationState
import ch.kevinjordil.helion.store.PublicationTarget
import ch.kevinjordil.helion.strava.activityDisplayName
import ch.kevinjordil.helion.strava.calorieEstimateFor
import ch.kevinjordil.helion.strava.externalIdFor
import ch.kevinjordil.helion.strava.tcxDownloadFileName
import ch.kevinjordil.helion.strava.writeTcx
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
}

private val START_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

/** How much of a non-2xx response body is kept as [Publication.lastErrorDetail] -- generous for a real diagnosis, short of pasting an entire HTML error page into the UI. */
private const val MAX_BODY_DETAIL_LENGTH = 500

/**
 * Sends one [ch.kevinjordil.helion.store.Activity] to the owner's own server as one
 * synchronous `multipart/form-data` POST -- see `README.md` for the exact request shape.
 *
 * Unlike [ch.kevinjordil.helion.strava.StravaPublisher], there is no asynchronous upload
 * job to resume: the request either lands in one call or it does not, so this reuses
 * [ch.kevinjordil.helion.store.Publication] and [PublicationState] purely as a *record* of
 * the last attempt (state, timestamp, failure reason and detail) rather than as a resumable
 * workflow -- [PublicationState.UPLOADING] and [Publication.uploadId]/[Publication.remoteId]
 * are simply never used for [PublicationTarget.CUSTOM_SERVER]: this transport has no
 * asynchronous job id and no server-assigned resource id to remember. A second send of the
 * same activity is still safe to repeat: [ch.kevinjordil.helion.strava.externalIdFor] gives
 * the receiving server the same stable id every time, which is what lets it recognise a
 * repeat rather than create a duplicate (see the `external_id` field in `README.md`).
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
        object Sent : Result()
        data class Failed(val reason: String) : Result()
    }

    suspend fun send(activityId: Long): Result {
        val activity = activities.get(activityId)
            ?: return Result.Failed(CustomServerFailureReason.REMOTE_ERROR)

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
        val tcx = writeTcx(activity.sport, activity.startTimestamp, activity.endTimestamp, samples, calories)
        val request = CustomServerSendRequest(
            tcx = tcx,
            fileName = tcxDownloadFileName(activity.sport, activity.startTimestamp, zone),
            sport = customServerSportSlug(activity.sport),
            title = activityDisplayName(activity),
            description = activity.notes.orEmpty(),
            startIso = START_TIME_FORMAT.format(Instant.ofEpochSecond(activity.startTimestamp).atZone(zone)),
            durationSeconds = (activity.endTimestamp - activity.startTimestamp).coerceAtLeast(0),
            calories = calories,
            externalId = externalIdFor(activityId),
        )

        return try {
            api.send(serverUrl, token, request)
            markSent(activityId)
            Result.Sent
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
            reason to "HTTP ${e.statusCode}: ${e.body.take(MAX_BODY_DETAIL_LENGTH)}"
        }
        else -> CustomServerFailureReason.NETWORK_ERROR to (e.message ?: "network error")
    }

    private suspend fun markSent(activityId: Long) {
        publications.upsert(
            Publication(
                activityId = activityId,
                target = PublicationTarget.CUSTOM_SERVER,
                remoteId = null,
                uploadId = null,
                state = PublicationState.PUBLISHED,
                lastAttempt = now(),
                lastError = null,
                lastErrorDetail = null,
            ),
        )
    }

    private suspend fun recordFailure(activityId: Long, reason: String, detail: String? = null) {
        val existing = publications.get(activityId, PublicationTarget.CUSTOM_SERVER)
        val row = existing?.copy(state = PublicationState.FAILED, lastAttempt = now(), lastError = reason, lastErrorDetail = detail)
            ?: Publication(
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
