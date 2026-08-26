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
import java.time.ZoneId

/** Machine-readable failure reasons stored in [Publication.lastError], mapped to French in the UI. */
object PublicationFailureReason {
    /** Never authorised on this device -- [ch.kevinjordil.helion.strava.StravaTokenStore.hasEverConnected] is still false. */
    const val NEVER_CONNECTED = "never_connected"

    /** Was authorised before; Strava has since revoked or rejected that authorisation. */
    const val AUTH_EXPIRED = "auth_expired"
    const val NOT_CONFIGURED = "not_configured"
    const val NETWORK_ERROR = "network_error"
    const val REMOTE_ERROR = "remote_error"
}

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

        if (existing != null && existing.state == PublicationState.PUBLISHED && existing.remoteId != null) {
            return try {
                api.updateActivity(accessToken, existing.remoteId, activityName(activity), stravaSportType(activity.sport))
                publications.upsert(existing.copy(lastAttempt = now(), lastError = null))
                Result.Published(existing.remoteId)
            } catch (e: Exception) {
                recordFailure(activityId, existing, PublicationFailureReason.NETWORK_ERROR)
                Result.Failed(PublicationFailureReason.NETWORK_ERROR)
            }
        }

        val resumableUploadId = existing?.uploadId
        if (existing != null && existing.state == PublicationState.UPLOADING && resumableUploadId != null) {
            return resolveUpload(activityId, accessToken, resumableUploadId)
        }

        val samples = minuteSamples.between(activity.startTimestamp, activity.endTimestamp)
        val calories = calorieEstimate(activity, samples)
        val tcx = writeTcx(activity.sport, activity.startTimestamp, activity.endTimestamp, samples, calories)
        val externalId = "helion-activity-$activityId"

        return try {
            val created = api.createUpload(accessToken, tcx, stravaSportType(activity.sport), activityName(activity), externalId)
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
            resolveUpload(activityId, accessToken, created.uploadId)
        } catch (e: Exception) {
            recordFailure(activityId, existing, PublicationFailureReason.NETWORK_ERROR)
            Result.Failed(PublicationFailureReason.NETWORK_ERROR)
        }
    }

    private suspend fun resolveUpload(activityId: Long, accessToken: String, uploadId: String): Result {
        val status = try {
            api.pollUpload(accessToken, uploadId)
        } catch (e: Exception) {
            // Poll itself failed (e.g. transient network) -- the row keeps its uploadId
            // untouched so the next attempt polls the same job rather than resubmitting.
            return Result.Failed(PublicationFailureReason.NETWORK_ERROR)
        }
        return when (status) {
            is UploadStatus.Done -> {
                markPublished(activityId, status.activityId)
                Result.Published(status.activityId)
            }
            is UploadStatus.Duplicate -> {
                markPublished(activityId, status.activityId)
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
                    ),
                )
                Result.Failed(PublicationFailureReason.REMOTE_ERROR)
            }
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

    private suspend fun recordFailure(activityId: Long, existing: Publication?, reason: String) {
        val row = existing?.copy(state = PublicationState.FAILED, lastAttempt = now(), lastError = reason)
            ?: Publication(
                activityId = activityId,
                target = PublicationTarget.STRAVA,
                remoteId = null,
                uploadId = null,
                state = PublicationState.FAILED,
                lastAttempt = now(),
                lastError = reason,
            )
        publications.upsert(row)
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
