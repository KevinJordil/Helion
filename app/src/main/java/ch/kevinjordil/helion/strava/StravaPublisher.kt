package ch.kevinjordil.helion.strava

import ch.kevinjordil.helion.calorie.ActivityCalorieEstimate
import ch.kevinjordil.helion.calorie.estimateActivityCalories
import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.store.ActivityDao
import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.store.MinuteSampleDao
import ch.kevinjordil.helion.store.Publication
import ch.kevinjordil.helion.store.PublicationDao
import ch.kevinjordil.helion.store.PublicationState
import ch.kevinjordil.helion.store.PublicationTarget
import ch.kevinjordil.helion.ui.settings.Profile
import java.net.HttpURLConnection
import java.time.ZoneId

/** Machine-readable failure reasons stored in [Publication.lastError], mapped to French in the UI. */
object PublicationFailureReason {
    /** Never authorised on this device -- [ch.kevinjordil.helion.strava.StravaTokenStore.hasEverConnected] is still false. */
    const val NEVER_CONNECTED = "never_connected"

    /** Was authorised before; Strava has since revoked or rejected that authorisation. */
    const val AUTH_EXPIRED = "auth_expired"
    const val NOT_CONFIGURED = "not_configured"

    /** The request never reached Strava, or its response never came back -- a genuine transport failure. */
    const val NETWORK_ERROR = "network_error"

    /**
     * Strava answered with a non-2xx status other than 401 -- a validation error, a
     * malformed request, a server-side failure. [Publication.lastErrorDetail] carries its
     * own message.
     */
    const val REMOTE_ERROR = "remote_error"

    /**
     * Strava answered 401 to an upload-related call made with a token [StravaAccessTokenProvider]
     * just handed over as valid. Distinct from [AUTH_EXPIRED]: that reason means there was no
     * usable token at all (the auth layer already knew and said so before any request went
     * out); this one means a token that *was* accepted moments earlier got rejected by the
     * upload/activity endpoints specifically, which is what a token authorised without the
     * `activity:write` scope looks like from here. Reconnecting fixes [AUTH_EXPIRED]; it only
     * fixes this if the owner actually grants the scope this time -- worth telling apart so a
     * scope problem is never reported as if it were an expired token.
     */
    const val UPLOAD_FORBIDDEN = "upload_forbidden"

    /**
     * Strava answered 403 to an upload-related call with its own "Application Status
     * Inactive" reason -- the Standard tier now requires an active Strava subscription on
     * the account that registered this app's client id, and this account does not have
     * one. Distinct from [UPLOAD_FORBIDDEN] (a 401, an insufficient-scope token): this is a
     * 403 that never depends on the token at all, reconnecting never fixes it, and the way
     * through is the save-to-Downloads action, not another publish attempt.
     */
    const val APPLICATION_INACTIVE = "application_inactive"
}

/**
 * Strava's own explanation for a rejected upload/activity-update call, with the HTTP status
 * prefixed -- [describeStravaError] alone only falls back to a bare status when the body
 * does not parse as Strava's documented error shape, so a parsed message would otherwise
 * carry no status at all. Never built from anything secret, same as [describeStravaError].
 */
internal fun describeStravaUploadError(exception: StravaHttpException): String {
    val described = describeStravaError(exception)
    return if (described.startsWith("HTTP ")) described else "HTTP ${exception.statusCode}: $described"
}

/**
 * Whether [exception] is Strava's specific "Application Status Inactive" 403 -- the
 * Standard-tier-requires-a-subscription rejection, distinct from an ordinary 403 (a
 * malformed request, a rate limit, ...) which stays [PublicationFailureReason.REMOTE_ERROR].
 * Checked against the raw response body rather than the already-parsed
 * [describeStravaError] text so this never depends on that function's exact formatting --
 * Strava's own wording for this case always mentions the application being inactive.
 */
internal fun isApplicationInactive(exception: StravaHttpException): Boolean =
    exception.statusCode == HttpURLConnection.HTTP_FORBIDDEN && exception.body.contains("nactive", ignoreCase = true)

/**
 * Drives one publish attempt of one [Activity] to Strava, end to end, in a way that is
 * always safe to call again: nothing here ever creates a second remote activity for an
 * activity this table has already recorded a [Publication] for.
 *
 * Three cases, in the order they are checked:
 * 1. Already [PublicationState.PUBLISHED] with a [Publication.remoteId] -- re-running this
 *    (the owner tapping publish again after editing the title, say) calls
 *    [StravaApi.updateActivity] on the existing remote activity. It never re-uploads the
 *    file, so it can never produce a duplicate.
 * 2. [PublicationState.UPLOADING] with a [Publication.uploadId] already stored -- an upload
 *    was submitted on a previous call (possibly one that then crashed, or lost network,
 *    before it got a final answer) and has not yet been resolved. This resumes by polling
 *    that same job id, never by submitting the file again.
 * 3. Otherwise: a fresh submit. The [Publication] row is written with the new
 *    [Publication.uploadId] *before* polling begins -- if the process dies between the
 *    submit call returning and the poll loop finishing, the row on disk already reflects
 *    case 2 for the next attempt, instead of a blindly re-submitted file.
 *
 * `external_id` on the upload is also set to `helion-activity-<id>`, Strava's own
 * duplicate-detection key -- belt and braces against the same activity ever being
 * uploaded twice even across a full app reinstall that lost the local publication row.
 */
class StravaPublisher(
    private val activities: ActivityDao,
    private val minuteSamples: MinuteSampleDao,
    private val publications: PublicationDao,
    private val tokenProvider: StravaAccessTokenProvider,
    private val api: StravaApi,
    // Optional so existing wiring/tests that have no profile to give still work: no
    // profile simply means no calorie figure goes into the TCX, never a guessed one.
    private val profile: Profile? = null,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    sealed class Result {
        data class Published(val remoteId: String) : Result()
        object StillProcessing : Result()
        data class Failed(val reason: String) : Result()
    }

    suspend fun publish(activityId: Long): Result {
        val activity = activities.get(activityId)
            ?: return Result.Failed(PublicationFailureReason.REMOTE_ERROR)
        val existing = publications.get(activityId, PublicationTarget.STRAVA)

        val accessToken = try {
            tokenProvider.validAccessToken()
        } catch (e: StravaNotConfiguredException) {
            recordFailure(activityId, existing, PublicationFailureReason.NOT_CONFIGURED)
            return Result.Failed(PublicationFailureReason.NOT_CONFIGURED)
        } catch (e: StravaAuthRequiredException) {
            val reason = if (e.neverConnected) PublicationFailureReason.NEVER_CONNECTED else PublicationFailureReason.AUTH_EXPIRED
            recordFailure(activityId, existing, reason)
            return Result.Failed(reason)
        }

        val name = activityName(activity)
        val sportType = stravaSportType(activity.sport)

        if (existing != null && existing.state == PublicationState.PUBLISHED && existing.remoteId != null) {
            return try {
                api.updateActivity(accessToken, existing.remoteId, name, sportType)
                publications.upsert(existing.copy(lastAttempt = now(), lastError = null, lastErrorDetail = null))
                Result.Published(existing.remoteId)
            } catch (e: Exception) {
                val (reason, detail) = classifyFailure(e)
                recordFailure(activityId, existing, reason, detail)
                Result.Failed(reason)
            }
        }

        val resumableUploadId = existing?.uploadId
        if (existing != null && existing.state == PublicationState.UPLOADING && resumableUploadId != null) {
            return resolveUpload(activityId, accessToken, resumableUploadId, name, sportType)
        }

        val samples = minuteSamples.between(activity.startTimestamp, activity.endTimestamp)
        val calories = calorieEstimate(activity, samples)
        val tcx = writeTcx(activity.sport, activity.startTimestamp, activity.endTimestamp, samples, calories)
        val externalId = "helion-activity-$activityId"

        return try {
            // Strava's `POST /uploads` has no `sport_type` field at all (see
            // [HttpStravaApi.createUpload]'s own kdoc) -- the real sport is set afterwards,
            // once the upload resolves, via `PUT /activities/{id}` (see [resolveUpload]).
            val created = api.createUpload(accessToken, tcx, name, externalId)
            // Written before the poll loop even starts -- see the class kdoc's case 3.
            publications.upsert(
                Publication(
                    activityId = activityId,
                    target = PublicationTarget.STRAVA,
                    remoteId = existing?.remoteId,
                    uploadId = created.uploadId,
                    state = PublicationState.UPLOADING,
                    lastAttempt = now(),
                    lastError = null,
                ),
            )
            resolveUpload(activityId, accessToken, created.uploadId, name, sportType)
        } catch (e: Exception) {
            val (reason, detail) = classifyFailure(e)
            recordFailure(activityId, existing, reason, detail)
            Result.Failed(reason)
        }
    }

    /**
     * Resolves an in-flight upload job -- freshly submitted or resumed from an interrupted
     * previous attempt -- and, on success, sets the real sport via [finalizeSport] since
     * `POST /uploads` never got the chance to (see [publish]'s own comment on that call).
     */
    private suspend fun resolveUpload(activityId: Long, accessToken: String, uploadId: String, name: String, sportType: String): Result {
        val status = try {
            api.pollUpload(accessToken, uploadId)
        } catch (e: Exception) {
            // Poll itself failed -- the row's state and uploadId are left exactly as they
            // were (see recordPollFailureDetail's own kdoc) so the next attempt polls the
            // same job rather than resubmitting.
            val (reason, detail) = classifyFailure(e)
            recordPollFailureDetail(activityId, reason, detail)
            return Result.Failed(reason)
        }
        return when (status) {
            is UploadStatus.Done -> {
                markPublished(activityId, status.activityId)
                finalizeSport(accessToken, status.activityId, name, sportType)
                Result.Published(status.activityId)
            }
            is UploadStatus.Duplicate -> {
                markPublished(activityId, status.activityId)
                finalizeSport(accessToken, status.activityId, name, sportType)
                Result.Published(status.activityId)
            }
            is UploadStatus.Processing -> {
                publications.upsert(
                    Publication(
                        activityId = activityId,
                        target = PublicationTarget.STRAVA,
                        remoteId = null,
                        uploadId = uploadId,
                        state = PublicationState.UPLOADING,
                        lastAttempt = now(),
                        lastError = null,
                    ),
                )
                Result.StillProcessing
            }
            is UploadStatus.Errored -> {
                publications.upsert(
                    Publication(
                        activityId = activityId,
                        target = PublicationTarget.STRAVA,
                        remoteId = null,
                        uploadId = null,
                        state = PublicationState.FAILED,
                        lastAttempt = now(),
                        lastError = PublicationFailureReason.REMOTE_ERROR,
                        lastErrorDetail = status.message,
                    ),
                )
                Result.Failed(PublicationFailureReason.REMOTE_ERROR)
            }
        }
    }

    /**
     * Sets the real sport and name on a just-uploaded remote activity via `PUT
     * /activities/{id}` -- the endpoint that actually accepts `sport_type`, unlike `POST
     * /uploads` (see [HttpStravaApi.createUpload]'s own kdoc). Best-effort: the file is
     * already safely and durably uploaded by the time this runs, so a failure here (the
     * token that just worked for the upload/poll calls would have to fail moments later)
     * is swallowed rather than turning an otherwise-successful publish into a failure --
     * the activity still exists on Strava, just possibly under the wrong sport until the
     * next publish attempt (the already-published path at the top of [publish] retries
     * this same call every time).
     */
    private fun finalizeSport(accessToken: String, remoteId: String, name: String, sportType: String) {
        try {
            api.updateActivity(accessToken, remoteId, name, sportType)
        } catch (e: Exception) {
            // Best-effort, see kdoc above.
        }
    }

    private suspend fun markPublished(activityId: Long, remoteId: String) {
        publications.upsert(
            Publication(
                activityId = activityId,
                target = PublicationTarget.STRAVA,
                remoteId = remoteId,
                uploadId = null,
                state = PublicationState.PUBLISHED,
                lastAttempt = now(),
                lastError = null,
            ),
        )
    }

    /**
     * [reason, detail] for an exception from any upload-related [api] call. A
     * [StravaHttpException] is a genuine answer from Strava -- its status and body say
     * why -- so it becomes [PublicationFailureReason.UPLOAD_FORBIDDEN] (401, most likely an
     * insufficient scope on an otherwise-valid token) or [PublicationFailureReason.REMOTE_ERROR]
     * (any other non-2xx, with Strava's own message as the detail). Anything else -- no
     * connectivity, a timeout, a reset connection -- never reached Strava at all and is
     * [PublicationFailureReason.NETWORK_ERROR]; its detail is the local exception's own
     * message, which never contains the access token, refresh token or client secret since
     * none of those flow through an [java.io.IOException] message.
     */
    private fun classifyFailure(e: Exception): Pair<String, String?> = when (e) {
        is StravaHttpException -> {
            val reason = when {
                e.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED -> PublicationFailureReason.UPLOAD_FORBIDDEN
                isApplicationInactive(e) -> PublicationFailureReason.APPLICATION_INACTIVE
                else -> PublicationFailureReason.REMOTE_ERROR
            }
            reason to describeStravaUploadError(e)
        }
        else -> PublicationFailureReason.NETWORK_ERROR to (e.message ?: "network error")
    }

    private suspend fun recordFailure(activityId: Long, existing: Publication?, reason: String, detail: String? = null) {
        val row = existing?.copy(state = PublicationState.FAILED, lastAttempt = now(), lastError = reason, lastErrorDetail = detail)
            ?: Publication(
                activityId = activityId,
                target = PublicationTarget.STRAVA,
                remoteId = null,
                uploadId = null,
                state = PublicationState.FAILED,
                lastAttempt = now(),
                lastError = reason,
                lastErrorDetail = detail,
            )
        publications.upsert(row)
    }

    /**
     * Records only [reason]/[detail] for a resumable poll's transient failure, leaving
     * [Publication.state] and [Publication.uploadId] exactly as they were -- unlike
     * [recordFailure], this never flips the row to [PublicationState.FAILED]: the upload
     * job is still alive on Strava's side, and the whole point of [PublicationState.UPLOADING]
     * is that the next attempt polls it again instead of resubmitting.
     */
    private suspend fun recordPollFailureDetail(activityId: Long, reason: String, detail: String?) {
        val row = publications.get(activityId, PublicationTarget.STRAVA) ?: return
        publications.upsert(row.copy(lastAttempt = now(), lastError = reason, lastErrorDetail = detail))
    }

    /**
     * The kcal figure to embed in the TCX, or null when there is no profile to estimate
     * from or no heart rate to estimate with -- [writeTcx] already treats a null the same
     * way it treats "not tracked" for distance and cadence: as a placeholder 0, never a
     * guess.
     */
    private fun calorieEstimate(activity: Activity, samples: List<MinuteSample>): Int? {
        val ownerProfile = profile ?: return null
        return when (val estimate = estimateActivityCalories(ownerProfile, activity.startTimestamp, zone, samples)) {
            is ActivityCalorieEstimate.Estimated -> estimate.kcal
            ActivityCalorieEstimate.ProfileIncomplete, ActivityCalorieEstimate.NoHeartRateData -> null
        }
    }

    private fun activityName(activity: Activity): String = activity.title?.takeIf { it.isNotBlank() } ?: "Helion"
}
