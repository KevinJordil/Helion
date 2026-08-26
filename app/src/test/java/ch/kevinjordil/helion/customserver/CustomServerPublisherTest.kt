package ch.kevinjordil.helion.customserver

import android.content.Context
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
import ch.kevinjordil.helion.ui.settings.CustomServerConfig
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

/**
 * A [CustomServerApi] whose behaviour each test scripts directly -- exactly what "no real
 * network calls in tests" requires, the same reasoning
 * [ch.kevinjordil.helion.strava.StravaPublisherTest]'s own fake documents.
 */
private class FakeCustomServerApi : CustomServerApi {
    var sendCalls = 0
    var throwOnSend: Exception? = null
    val seenRequests = mutableListOf<CustomServerSendRequest>()

    override fun send(serverUrl: String, token: String, request: CustomServerSendRequest) {
        sendCalls++
        seenRequests.add(request)
        throwOnSend?.let { throw it }
    }
}

/**
 * [CustomServerPublisher.send] is the one entry point Helion's custom-server export goes
 * through: this covers a clean send, the calorie-omission rule, external-id stability
 * across repeat sends, and the three failure kinds the activity detail screen must show
 * distinctly -- a non-2xx response, an unreachable host, and a rejected token -- per the
 * module's own brief.
 */
@RunWith(RobolectricTestRunner::class)
class CustomServerPublisherTest {

    private lateinit var db: HelionDatabase
    private lateinit var api: FakeCustomServerApi
    private lateinit var config: CustomServerConfig
    private lateinit var publisher: CustomServerPublisher
    private var now = 1_700_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HelionDatabase::class.java,
        ).allowMainThreadQueries().build()
        api = FakeCustomServerApi()
        val context = ApplicationProvider.getApplicationContext<Context>()
        config = CustomServerConfig(context).apply {
            serverUrl = "https://example.com/ingest"
            token = "shared-secret-token"
        }
        publisher = CustomServerPublisher(
            activities = db.activities(),
            minuteSamples = db.minuteSamples(),
            publications = db.publications(),
            config = config,
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
                notes = "Match serré",
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
    fun `a clean send records PUBLISHED and clears any previous failure`() = runTest {
        val activityId = seedActivity()

        val result = publisher.send(activityId)

        assertEquals(CustomServerPublisher.Result.Sent, result)
        assertEquals(1, api.sendCalls)
        val row = db.publications().get(activityId, PublicationTarget.CUSTOM_SERVER)
        assertEquals(PublicationState.PUBLISHED, row?.state)
        assertNull(row?.lastError)
    }

    @Test
    fun `the request carries every documented field, including a real title, description and sport slug`() = runTest {
        val activityId = seedActivity()

        publisher.send(activityId)

        val request = api.seenRequests.single()
        assertEquals("badminton", request.sport)
        assertEquals("Badminton du soir", request.title)
        assertEquals("Match serré", request.description)
        assertEquals(1_800L, request.durationSeconds)
    }

    @Test
    fun `calories is omitted when there is no profile to estimate from`() = runTest {
        // publisher in setUp() has no profile wired in -- see CustomServerPublisher's own
        // "optional so tests with no profile still work" kdoc.
        val activityId = seedActivity()

        publisher.send(activityId)

        assertNull(api.seenRequests.single().calories)
    }

    @Test
    fun `the external id is stable across repeat sends of the same activity`() = runTest {
        val activityId = seedActivity()

        publisher.send(activityId)
        publisher.send(activityId)

        val ids = api.seenRequests.map { it.externalId }
        assertEquals(2, ids.size)
        assertEquals(ids[0], ids[1])
        assertEquals("helion-activity-$activityId", ids[0])
    }

    @Test
    fun `a non-2xx response is recorded as a remote error carrying the real status and body`() = runTest {
        val activityId = seedActivity()
        api.throwOnSend = CustomServerHttpException(500, "internal server error, try again later")

        val result = publisher.send(activityId)

        assertEquals(CustomServerPublisher.Result.Failed(CustomServerFailureReason.REMOTE_ERROR), result)
        val row = db.publications().get(activityId, PublicationTarget.CUSTOM_SERVER)
        assertEquals(PublicationState.FAILED, row?.state)
        assertEquals(CustomServerFailureReason.REMOTE_ERROR, row?.lastError)
        assertTrue(row?.lastErrorDetail.orEmpty().contains("500"))
        assertTrue(row?.lastErrorDetail.orEmpty().contains("internal server error"))
    }

    @Test
    fun `an unreachable host is recorded as a network error, distinct from a remote error`() = runTest {
        val activityId = seedActivity()
        api.throwOnSend = IOException("Unable to resolve host")

        val result = publisher.send(activityId)

        assertEquals(CustomServerPublisher.Result.Failed(CustomServerFailureReason.NETWORK_ERROR), result)
        val row = db.publications().get(activityId, PublicationTarget.CUSTOM_SERVER)
        assertEquals(CustomServerFailureReason.NETWORK_ERROR, row?.lastError)
        assertEquals("Unable to resolve host", row?.lastErrorDetail)
        assertNotEquals(CustomServerFailureReason.REMOTE_ERROR, row?.lastError)
    }

    @Test
    fun `a rejected token is recorded distinctly from a generic remote error`() = runTest {
        val activityId = seedActivity()
        api.throwOnSend = CustomServerHttpException(401, "invalid or expired token")

        val result = publisher.send(activityId)

        assertEquals(CustomServerPublisher.Result.Failed(CustomServerFailureReason.UNAUTHORIZED), result)
        val row = db.publications().get(activityId, PublicationTarget.CUSTOM_SERVER)
        assertEquals(CustomServerFailureReason.UNAUTHORIZED, row?.lastError)
        assertTrue(row?.lastErrorDetail.orEmpty().contains("401"))
        assertNotEquals(CustomServerFailureReason.REMOTE_ERROR, row?.lastError)
        assertNotEquals(CustomServerFailureReason.NETWORK_ERROR, row?.lastError)
    }

    @Test
    fun `the token itself never appears in a recorded failure detail`() = runTest {
        val activityId = seedActivity()
        api.throwOnSend = IOException("connection reset")

        publisher.send(activityId)

        val row = db.publications().get(activityId, PublicationTarget.CUSTOM_SERVER)
        assertTrue(row?.lastErrorDetail.orEmpty().let { "shared-secret-token" !in it })
    }

    @Test
    fun `no url or token configured fails as not configured, without ever touching the network`() = runTest {
        config.serverUrl = null
        config.token = null
        val activityId = seedActivity()

        val result = publisher.send(activityId)

        assertEquals(CustomServerPublisher.Result.Failed(CustomServerFailureReason.NOT_CONFIGURED), result)
        assertEquals(0, api.sendCalls)
    }

    @Test
    fun `a malformed url fails as invalid, without ever touching the network`() = runTest {
        config.serverUrl = "not a url"
        val activityId = seedActivity()

        val result = publisher.send(activityId)

        assertEquals(CustomServerPublisher.Result.Failed(CustomServerFailureReason.INVALID_URL), result)
        assertEquals(0, api.sendCalls)
    }

    @Test
    fun `a plain-HTTP url is refused until explicitly allowed`() = runTest {
        config.serverUrl = "http://192.168.1.50:8080/ingest"
        config.allowPlainHttp = false
        val activityId = seedActivity()

        val result = publisher.send(activityId)

        assertEquals(CustomServerPublisher.Result.Failed(CustomServerFailureReason.PLAIN_HTTP_NOT_CONFIRMED), result)
        assertEquals(0, api.sendCalls)
    }

    @Test
    fun `a plain-HTTP url sends once explicitly allowed`() = runTest {
        config.serverUrl = "http://192.168.1.50:8080/ingest"
        config.allowPlainHttp = true
        val activityId = seedActivity()

        val result = publisher.send(activityId)

        assertEquals(CustomServerPublisher.Result.Sent, result)
        assertEquals(1, api.sendCalls)
    }
}
