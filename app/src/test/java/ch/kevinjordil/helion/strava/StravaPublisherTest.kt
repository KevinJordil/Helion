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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A [StravaAccessTokenProvider] that never touches the network. */
private class FakeTokenProvider(private var token: String? = "valid-token") : StravaAccessTokenProvider {
    var throwOnNext: Exception? = null

    override suspend fun validAccessToken(): String {
        throwOnNext?.let { throw it }
        return token ?: throw StravaAuthRequiredException("no token in test")
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
    val seenExternalIds = mutableListOf<String>()
    val seenSportTypes = mutableListOf<String>()
    val seenTcx = mutableListOf<String>()

    override fun createUpload(accessToken: String, tcx: String, sportType: String, name: String, externalId: String): UploadCreated {
        createUploadCalls++
        throwOnCreate?.let { throw it }
        seenExternalIds.add(externalId)
        seenSportTypes.add(sportType)
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
        assertEquals(listOf("Badminton"), api.seenSportTypes)

        val row = db.publications().get(activityId, PublicationTarget.STRAVA)
        assertEquals(PublicationState.PUBLISHED, row?.state)
        assertEquals("remote-42", row?.remoteId)
        assertNull(row?.uploadId)
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
        tokenProvider.throwOnNext = StravaAuthRequiredException("expired")

        val result = publisher.publish(activityId)

        assertEquals(StravaPublisher.Result.Failed(PublicationFailureReason.AUTH_REQUIRED), result)
        assertEquals(0, api.createUploadCalls)
        val row = db.publications().get(activityId, PublicationTarget.STRAVA)
        assertEquals(PublicationState.FAILED, row?.state)
        assertEquals(PublicationFailureReason.AUTH_REQUIRED, row?.lastError)
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
}
