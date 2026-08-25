package ch.kevinjordil.helion.strava

/** What a just-submitted upload got back from Strava: its asynchronous job id. */
data class UploadCreated(val uploadId: String)

/** How Strava's asynchronous upload-processing job currently stands (`GET /uploads/{id}`). */
sealed class UploadStatus {
    /** Fully processed; [activityId] is the resulting Strava activity. */
    data class Done(val activityId: String) : UploadStatus()

    /** Still queued or processing -- poll again later, do not resubmit the file. */
    object Processing : UploadStatus()

    /**
     * Strava recognised the `external_id` as one already uploaded -- this activity was
     * already published under [activityId] by an earlier, possibly-interrupted attempt.
     * Treated the same as [Done]: this is exactly the duplicate-avoidance path idempotency
     * depends on.
     */
    data class Duplicate(val activityId: String) : UploadStatus()

    /** Strava rejected the file or the job failed; [message] is its own error text. */
    data class Errored(val message: String) : UploadStatus()
}

/**
 * The three Strava calls [StravaPublisher] needs. Kept as an interface so tests can fake
 * Strava's asynchronous behaviour (including the interrupted-upload case) without a real
 * network call; [HttpStravaApi] is the only implementation used outside tests.
 */
interface StravaApi {

    /**
     * Submits [tcx] as a new upload. [externalId] is Strava's own de-duplication key: a
     * second submit with the same value that Strava has already seen resolves to
     * [UploadStatus.Duplicate] instead of creating a second activity.
     */
    fun createUpload(accessToken: String, tcx: String, sportType: String, name: String, externalId: String): UploadCreated

    fun pollUpload(accessToken: String, uploadId: String): UploadStatus

    /** Updates an already-published activity's name and sport in place -- never re-uploads the file. */
    fun updateActivity(accessToken: String, remoteId: String, name: String, sportType: String)
}
