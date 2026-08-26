package ch.kevinjordil.helion.strava

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.store.ActivityOrigin
import ch.kevinjordil.helion.store.ActivityStatus
import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.store.PublicationState
import ch.kevinjordil.helion.store.PublicationTarget
import ch.kevinjordil.helion.store.SportType
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A [StravaAccessTokenProvider] that never touches the network. */
private class FakeTokenProvider(private var token: String? = "valid-token") : StravaAccessTokenProvider {
    var throwOnNext: Exception? = null
    var neverConnected: Boolean = false

    override suspend fun validAccessToken(): String {
        throwOnNext?.let { throw it }
        return token ?: throw StravaAuthRequiredException("no token in test", neverConnected = neverConnected)
    }
}

/**
 * A [StravaApi] whose behaviour each test script directly, standing in for Strava's real
 * asynchronous upload/poll/update calls -- exactly what "do not make real network calls in
 * tests" requires.
 */
private class FakeStravaApi : StravaApi {
    var nextUploadId = "upload-1"
    var createUploadCalls = 0
    var pollCalls = 0
    var updateCalls = 0
    var pollResult: UploadStatus = UploadStatus.Processing
    var throwOnCreate: Exception? = null
    var throwOnPoll: Exception? = null
    var throwOnUpdate: Exception? = null
    val seenExternalIds = mutableListOf<String>()
    val seenTcx = mutableListOf<String>()
    val seenUpdateNames = mutableListOf<String>()
    val seenUpdateSportTypes = mutableListOf<String>()

    override fun createUpload(accessToken: String, tcx: String, name: String, externalId: String): UploadCreated {
        createUploadCalls++
        throwOnCreate?.let { throw it }
        seenExternalIds.add(externalId)
        seenTcx.add(tcx)
        return UploadCreated(nextUploadId)
    }

    override fun pollUpload(accessToken: String, uploadId: String): UploadStatus {
        pollCalls++
        throwOnPoll?.let { throw it }
        return pollResult
    }

    override fun updateActivity(accessToken: String, remoteId: String, name: String, sportType: String) {
        updateCalls++
        throwOnUpdate?.let { throw it }
        seenUpdateNames.add(name)
        seenUpdateSportTypes.add(sportType)
    }
}

/**
 * The idempotency contract [StravaPublisher] exists to guarantee: a second publish attempt
 * never creates a second remote activity, whether the first attempt fully succeeded,
 * failed outright, or -- the case that matters most -- was interrupted between submitting
 * the upload and learning its result.
 */
@RunWith(RobolectricTestRunner::class)
class StravaPublisherTest {

    private lateinit var db: HelionDatabase
    private lateinit var api: FakeStravaApi
    private lateinit var tokenProvider: FakeTokenProvider
    private lateinit var publisher: StravaPublisher
    private var now = 1_700_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HelionDatabase::class.java,
        ).allowMainThreadQueries().build()
        api = FakeStravaApi()
        tokenProvider = FakeTokenProvider()
        publisher = StravaPublisher(
            activities = db.activities(),
            minuteSamples = db.minuteSamples(),
            publications = db.publications(),
            tokenProvider = tokenProvider,
            api = api,
            now = { now },
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedActivity(start: Long = now - 1_800, end: Long = now): Long {
        val id = db.activities().upsert(
            Activity(
                startTimestamp = start,
                endTimestamp = end,
                sport = SportType.BADMINTON,
                title = "Badminton du soir",
                notes = null,
                origin = ActivityOrigin.MANUAL,
                status = ActivityStatus.CONFIRMED,
            ),
        )
        db.minuteSamples().upsertAll(
            listOf(
                MinuteSample(timestamp = start, steps = null, intensity = null, rawKind = null, heartRate = 120, sleepStage = null),
                MinuteSample(timestamp = start + 60, steps = null, intensity = null, rawKind = null, heartRate = 140, sleepStage = null),
            ),
        )
        return id
    }

    @Test
    fun `a fresh publish submits once and resolves to published when the poll returns done`() = runTest {
        val activityId = seedActivity()
        api.pollResult = UploadStatus.Done("remote-42")

        val result = publisher.publish(activityId)

        assertEquals(StravaPublisher.Result.Published("remote-42"), result)
        assertEquals(1, api.createUploadCalls)
        assertEquals(1, api.pollCalls)
        assertEquals(listOf("helion-activity-$activityId"), api.seenExternalIds)
        // The sport is never sent to POST /uploads (it has no field for one) -- it is set
        // afterwards via PUT /activities/{id}, once the upload has actually resolved.
        assertEquals(1, api.updateCalls)
        assertEquals(listOf("Badminton"), api.seenUpdateSportTypes)

        val row = db.publications().get(activityId, PublicationTarget.STRAVA)
        assertEquals(PublicationState.PUBLISHED, row?.state)
        assertEquals("remote-42", row?.remoteId)
        assertNull(row?.uploadId)
    }

    @Test
    fun `the detection context never reaches the uploaded TCX`() = runTest {
        val activityId = db.activities().upsert(
            Activity(
                startTimestamp = now - 1_800,
                endTimestamp = now,
                sport = SportType.BADMINTON,
                title = "Entraînement du lundi",
                notes = "Bonne séance",
                detectionContext = "Fréquence cardiaque 120–150 bpm (repos habituel ≈ 58 bpm).",
                origin = ActivityOrigin.SLOT,
                status = ActivityStatus.CONFIRMED,
            ),
        )
        api.pollResult = UploadStatus.Done("remote-42")

        publisher.publish(activityId)

        val tcx = api.seenTcx.single()
        assertTrue(!tcx.contains("Fréquence cardiaque"))
        assertTrue(!tcx.contains("Bonne séance"))
    }

    @Test
    fun `an interrupted upload is resumed by polling, never resubmitted`() = runTest {
        val activityId = seedActivity()

        // Simulate a first attempt that submitted the file and was killed before the poll
        // ever ran: only the UPLOADING row exists on disk, exactly what StravaPublisher's
        // own submit path leaves behind before it starts polling.
        db.publications().upsert(
            ch.kevinjordil.helion.store.Publication(
                activityId = activityId,
                target = PublicationTarget.STRAVA,
                remoteId = null,
                uploadId = "upload-in-flight",
                state = PublicationState.UPLOADING,
                lastAttempt = now - 60,
                lastError = null,
            ),
        )
        api.pollResult = UploadStatus.Done("remote-99")

        val result = publisher.publish(activityId)

        assertEquals(StravaPublisher.Result.Published("remote-99"), result)
        assertEquals(0, api.createUploadCalls) // never re-uploaded
        assertEquals(1, api.pollCalls)

        val row = db.publications().get(activityId, PublicationTarget.STRAVA)
        assertEquals(PublicationState.PUBLISHED, row?.state)
        assertEquals("remote-99", row?.remoteId)
    }

    @Test
    fun `a still-processing poll leaves the row resumable with the same upload id`() = runTest {
        val activityId = seedActivity()
        api.pollResult = UploadStatus.Processing

        val result = publisher.publish(activityId)

        assertEquals(StravaPublisher.Result.StillProcessing, result)
        val row = db.publications().get(activityId, PublicationTarget.STRAVA)
        assertEquals(PublicationState.UPLOADING, row?.state)
        assertEquals(api.nextUploadId, row?.uploadId)

        // A second attempt while still processing must poll the same job, not resubmit.
        val second = publisher.publish(activityId)
        assertEquals(StravaPublisher.Result.StillProcessing, second)
        assertEquals(1, api.createUploadCalls)
        assertEquals(2, api.pollCalls)
    }

    @Test
    fun `a duplicate response from Strava is treated as already published`() = runTest {
        val activityId = seedActivity()
        api.pollResult = UploadStatus.Duplicate("remote-existing")

        val result = publisher.publish(activityId)

        assertEquals(StravaPublisher.Result.Published("remote-existing"), result)
        val row = db.publications().get(activityId, PublicationTarget.STRAVA)
        assertEquals(PublicationState.PUBLISHED, row?.state)
        assertEquals("remote-existing", row?.remoteId)
    }

    @Test
    fun `publishing an already-published activity again updates it instead of re-uploading`() = runTest {
        val activityId = seedActivity()
        db.publications().upsert(
            ch.kevinjordil.helion.store.Publication(
                activityId = activityId,
                target = PublicationTarget.STRAVA,
                remoteId = "remote-1",
                uploadId = null,
                state = PublicationState.PUBLISHED,
                lastAttempt = now - 3_600,
                lastError = null,
            ),
        )

        val result = publisher.publish(activityId)

        assertEquals(StravaPublisher.Result.Published("remote-1"), result)
        assertEquals(0, api.createUploadCalls)
        assertEquals(1, api.updateCalls)
    }

    @Test
    fun `a poll error marks the publication failed and clears the upload id`() = runTest {
        val activityId = seedActivity()
        api.pollResult = UploadStatus.Errored("file rejected")

        val result = publisher.publish(activityId)

        assertTrue(result is StravaPublisher.Result.Failed)
        val row = db.publications().get(activityId, PublicationTarget.STRAVA)
        assertEquals(PublicationState.FAILED, row?.state)
        assertNull(row?.uploadId)
    }

    @Test
    fun `missing authorization is reported plainly and never attempts an upload`() = runTest {
        val activityId = seedActivity()
        tokenProvider.throwOnNext = StravaAuthRequiredException("expired", neverConnected = false)

        val result = publisher.publish(activityId)

        assertEquals(StravaPublisher.Result.Failed(PublicationFailureReason.AUTH_EXPIRED), result)
        assertEquals(0, api.createUploadCalls)
        val row = db.publications().get(activityId, PublicationTarget.STRAVA)
        assertEquals(PublicationState.FAILED, row?.state)
        assertEquals(PublicationFailureReason.AUTH_EXPIRED, row?.lastError)
    }

    @Test
    fun `never having connected is reported with a different reason than an expired authorization`() = runTest {
        val activityId = seedActivity()
        tokenProvider.throwOnNext = StravaAuthRequiredException("no refresh token stored", neverConnected = true)

        val result = publisher.publish(activityId)

        assertEquals(StravaPublisher.Result.Failed(PublicationFailureReason.NEVER_CONNECTED), result)
        val row = db.publications().get(activityId, PublicationTarget.STRAVA)
        assertEquals(PublicationFailureReason.NEVER_CONNECTED, row?.lastError)
        assertTrue(PublicationFailureReason.NEVER_CONNECTED != PublicationFailureReason.AUTH_EXPIRED)
    }

    @Test
    fun `a network failure while resuming a poll keeps the upload id for the next attempt`() = runTest {
        val activityId = seedActivity()
        db.publications().upsert(
            ch.kevinjordil.helion.store.Publication(
                activityId = activityId,
                target = PublicationTarget.STRAVA,
                remoteId = null,
                uploadId = "upload-in-flight",
                state = PublicationState.UPLOADING,
                lastAttempt = now - 60,
                lastError = null,
            ),
        )
        api.throwOnPoll = java.io.IOException("network down")

        val result = publisher.publish(activityId)

        assertEquals(StravaPublisher.Result.Failed(PublicationFailureReason.NETWORK_ERROR), result)
        assertEquals(0, api.createUploadCalls)
        val row = db.publications().get(activityId, PublicationTarget.STRAVA)
        assertEquals("upload-in-flight", row?.uploadId)
        assertEquals(PublicationState.UPLOADING, row?.state)
    }

    @Test
    fun `a complete profile makes the uploaded TCX carry a real calorie estimate`() = runTest {
        val profile = ch.kevinjordil.helion.ui.settings.Profile(ApplicationProvider.getApplicationContext())
        profile.dateOfBirthEpochDay = java.time.LocalDate.of(1994, 1, 1).toEpochDay()
        profile.weightKg = 70f
        profile.sex = ch.kevinjordil.helion.ui.settings.Sex.MALE

        val publisherWithProfile = StravaPublisher(
            activities = db.activities(),
            minuteSamples = db.minuteSamples(),
            publications = db.publications(),
            tokenProvider = tokenProvider,
            api = api,
            profile = profile,
            zone = java.time.ZoneOffset.UTC,
            now = { now },
        )
        val activityId = seedActivity()
        api.pollResult = UploadStatus.Done("remote-1")

        publisherWithProfile.publish(activityId)

        assertEquals(1, api.seenTcx.size)
        assertTrue(api.seenTcx.single().contains("<Calories>") && !api.seenTcx.single().contains("<Calories>0<"))
    }

    @Test
    fun `no profile means the uploaded TCX keeps the placeholder Calories value, never a guess`() = runTest {
        val activityId = seedActivity()
        api.pollResult = UploadStatus.Done("remote-2")

        publisher.publish(activityId)

        assertEquals(1, api.seenTcx.size)
        assertTrue(api.seenTcx.single().contains("<Calories>0<"))
    }

    /**
     * The defect this whole file's own kdoc leads with: [StravaHttpException] extends
     * [IOException], so a bare `catch (e: Exception)` used to map an HTTP error response,
     * a 401, and a genuine transport failure to the exact same [PublicationFailureReason.NETWORK_ERROR] --
     * discarding the status code and Strava's own response body every time. These three
     * tests are the guarantee that each of those three cases now produces a distinct
     * outcome, with Strava's message preserved in [ch.kevinjordil.helion.store.Publication.lastErrorDetail]
     * wherever Strava actually answered.
     */
    @Test
    fun `an HTTP error response from createUpload is reported as a remote error carrying Strava's own message`() = runTest {
        val activityId = seedActivity()
        api.throwOnCreate = StravaHttpException(
            statusCode = 400,
            body = """{"message":"Bad Request","errors":[{"resource":"Upload","field":"data_type","code":"invalid"}]}""",
            message = "Strava request failed with HTTP 400",
        )

        val result = publisher.publish(activityId)

        assertEquals(StravaPublisher.Result.Failed(PublicationFailureReason.REMOTE_ERROR), result)
        val row = db.publications().get(activityId, PublicationTarget.STRAVA)
        assertEquals(PublicationFailureReason.REMOTE_ERROR, row?.lastError)
        assertTrue(row?.lastErrorDetail.orEmpty().contains("400"))
        assertTrue(row?.lastErrorDetail.orEmpty().contains("Bad Request"))
    }

    @Test
    fun `a 401 from createUpload is reported as an insufficient-scope upload failure, never as an expired token`() = runTest {
        val activityId = seedActivity()
        api.throwOnCreate = StravaHttpException(
            statusCode = 401,
            body = """{"message":"Authorization Error","errors":[{"resource":"Athlete","field":"activity:write","code":"missing"}]}""",
            message = "Strava request failed with HTTP 401",
        )

        val result = publisher.publish(activityId)

        assertEquals(StravaPublisher.Result.Failed(PublicationFailureReason.UPLOAD_FORBIDDEN), result)
        assertNotEquals(PublicationFailureReason.AUTH_EXPIRED, PublicationFailureReason.UPLOAD_FORBIDDEN)
        val row = db.publications().get(activityId, PublicationTarget.STRAVA)
        assertEquals(PublicationFailureReason.UPLOAD_FORBIDDEN, row?.lastError)
        assertTrue(row?.lastErrorDetail.orEmpty().contains("401"))
    }

    @Test
    fun `a 403 with Strava's inactive-application wording is reported distinctly from an ordinary remote error`() = runTest {
        val activityId = seedActivity()
        api.throwOnCreate = StravaHttpException(
            statusCode = 403,
            body = """{"message":"Forbidden","errors":[{"resource":"Application","field":"Status","code":"Inactive"}]}""",
            message = "Strava request failed with HTTP 403",
        )

        val result = publisher.publish(activityId)

        assertEquals(StravaPublisher.Result.Failed(PublicationFailureReason.APPLICATION_INACTIVE), result)
        assertNotEquals(PublicationFailureReason.REMOTE_ERROR, PublicationFailureReason.APPLICATION_INACTIVE)
        val row = db.publications().get(activityId, PublicationTarget.STRAVA)
        assertEquals(PublicationFailureReason.APPLICATION_INACTIVE, row?.lastError)
        assertTrue(row?.lastErrorDetail.orEmpty().contains("403"))
    }

    @Test
    fun `an ordinary 403 without the inactive-application wording stays a plain remote error`() = runTest {
        val activityId = seedActivity()
        api.throwOnCreate = StravaHttpException(
            statusCode = 403,
            body = """{"message":"Forbidden","errors":[{"resource":"Upload","field":"file","code":"denied"}]}""",
            message = "Strava request failed with HTTP 403",
        )

        val result = publisher.publish(activityId)

        assertEquals(StravaPublisher.Result.Failed(PublicationFailureReason.REMOTE_ERROR), result)
        val row = db.publications().get(activityId, PublicationTarget.STRAVA)
        assertEquals(PublicationFailureReason.REMOTE_ERROR, row?.lastError)
    }

    @Test
    fun `a genuine transport failure from createUpload is reported as a network error, distinct from the other two`() = runTest {
        val activityId = seedActivity()
        api.throwOnCreate = IOException("Unable to resolve host")

        val result = publisher.publish(activityId)

        assertEquals(StravaPublisher.Result.Failed(PublicationFailureReason.NETWORK_ERROR), result)
        val row = db.publications().get(activityId, PublicationTarget.STRAVA)
        assertEquals(PublicationFailureReason.NETWORK_ERROR, row?.lastError)
        assertEquals("Unable to resolve host", row?.lastErrorDetail)
        assertNotEquals(row?.lastError, PublicationFailureReason.REMOTE_ERROR)
        assertNotEquals(row?.lastError, PublicationFailureReason.UPLOAD_FORBIDDEN)
    }

    @Test
    fun `a poll error carrying an HTTP status is classified the same way as a createUpload error`() = runTest {
        val activityId = seedActivity()
        db.publications().upsert(
            ch.kevinjordil.helion.store.Publication(
                activityId = activityId,
                target = PublicationTarget.STRAVA,
                remoteId = null,
                uploadId = "upload-in-flight",
                state = PublicationState.UPLOADING,
                lastAttempt = now - 60,
                lastError = null,
            ),
        )
        api.throwOnPoll = StravaHttpException(statusCode = 401, body = """{"message":"Authorization Error"}""", message = "HTTP 401")

        val result = publisher.publish(activityId)

        assertEquals(StravaPublisher.Result.Failed(PublicationFailureReason.UPLOAD_FORBIDDEN), result)
        val row = db.publications().get(activityId, PublicationTarget.STRAVA)
        // Still resumable: a poll failure never flips the row to FAILED or drops uploadId.
        assertEquals(PublicationState.UPLOADING, row?.state)
        assertEquals("upload-in-flight", row?.uploadId)
        assertEquals(PublicationFailureReason.UPLOAD_FORBIDDEN, row?.lastError)
        assertTrue(row?.lastErrorDetail.orEmpty().contains("401"))
    }

    @Test
    fun `Strava's own error message from a poll result is preserved, not discarded for a bare reason code`() = runTest {
        val activityId = seedActivity()
        api.pollResult = UploadStatus.Errored("file is a duplicate of another upload")

        val result = publisher.publish(activityId)

        assertTrue(result is StravaPublisher.Result.Failed)
        val row = db.publications().get(activityId, PublicationTarget.STRAVA)
        assertEquals(PublicationFailureReason.REMOTE_ERROR, row?.lastError)
        assertEquals("file is a duplicate of another upload", row?.lastErrorDetail)
    }

    @Test
    fun `a failure finalising the sport after a successful upload never turns success into failure`() = runTest {
        val activityId = seedActivity()
        api.pollResult = UploadStatus.Done("remote-42")
        api.throwOnUpdate = IOException("transient")

        val result = publisher.publish(activityId)

        assertEquals(StravaPublisher.Result.Published("remote-42"), result)
        val row = db.publications().get(activityId, PublicationTarget.STRAVA)
        assertEquals(PublicationState.PUBLISHED, row?.state)
    }
}
