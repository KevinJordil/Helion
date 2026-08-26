package ch.kevinjordil.helion.customserver

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildCustomServerMultipartBody] is the entire request shape described in `README.md`'s
 * custom-server contract; this exercises it directly, with no [java.net.HttpURLConnection]
 * and no real network call, exactly as `README.md`'s field table promises.
 */
class HttpCustomServerApiTest {

    private val boundary = "test-boundary"

    private fun request(calories: Int? = 480) = CustomServerSendRequest(
        tcx = "<TrainingCenterDatabase/>",
        fileName = "badminton-2026-08-26-2010.tcx",
        sport = "badminton",
        title = "Badminton du soir",
        description = "Match serré",
        startIso = "2026-08-26T20:10:00+02:00",
        durationSeconds = 3_600,
        calories = calories,
        externalId = "helion-activity-42",
    )

    private fun bodyText(calories: Int? = 480): String =
        buildCustomServerMultipartBody(boundary, request(calories)).toString(Charsets.UTF_8)

    @Test
    fun `every documented field is present with its own value`() {
        val text = bodyText()
        assertTrue(text.contains("name=\"sport\""))
        assertTrue(text.contains("badminton"))
        assertTrue(text.contains("name=\"title\""))
        assertTrue(text.contains("Badminton du soir"))
        assertTrue(text.contains("name=\"description\""))
        assertTrue(text.contains("Match serré"))
        assertTrue(text.contains("name=\"start\""))
        assertTrue(text.contains("2026-08-26T20:10:00+02:00"))
        assertTrue(text.contains("name=\"duration_seconds\""))
        assertTrue(text.contains("3600"))
        assertTrue(text.contains("name=\"external_id\""))
        assertTrue(text.contains("helion-activity-42"))
    }

    @Test
    fun `the file field carries the TCX under the Downloads-style file name`() {
        val text = bodyText()
        assertTrue(text.contains("name=\"file\"; filename=\"badminton-2026-08-26-2010.tcx\""))
        assertTrue(text.contains("<TrainingCenterDatabase/>"))
    }

    @Test
    fun `calories is sent when known`() {
        val text = bodyText(calories = 480)
        assertTrue(text.contains("name=\"calories\""))
        assertTrue(text.contains("480"))
    }

    @Test
    fun `calories is omitted entirely when null, never sent as zero`() {
        val text = bodyText(calories = null)
        assertFalse(text.contains("name=\"calories\""))
        // A stray "0" from some other field is not proof of a bug, but the field's own
        // Content-Disposition header must never appear.
        assertFalse(text.contains("\"calories\""))
    }

    @Test
    fun `the external id is stable for the same request, so a repeat send is recognisable`() {
        val first = bodyText()
        val second = bodyText()
        assertEquals(first, second)
        assertTrue(first.contains("helion-activity-42"))
    }

    @Test
    fun `the body ends with the closing boundary`() {
        val text = bodyText()
        assertTrue(text.trimEnd().endsWith("--$boundary--"))
    }
}

/**
 * A minimal [HttpURLConnection] stub -- just enough of it for [readCustomServerResponse] to
 * exercise real response-reading code with no real network call: a scripted status code and
 * a scripted body stream (or one that throws, for the "not readable as text" case).
 */
private class FakeHttpURLConnection(
    private val statusCode: Int,
    private val stream: InputStream?,
) : HttpURLConnection(URL("https://example.com/ingest")) {
    override fun getResponseCode(): Int = statusCode
    override fun getInputStream(): InputStream = stream ?: throw IOException("no stream")
    override fun getErrorStream(): InputStream? = stream
    override fun disconnect() {}
    override fun usingProxy(): Boolean = false
    override fun connect() {}
}

/**
 * [readCustomServerResponse] is the one place that turns a raw [HttpURLConnection] into
 * either a [CustomServerResponse] (2xx) or a thrown [CustomServerHttpException] (anything
 * else) -- this exercises both branches, plus the "body could not be read as text" fallback
 * [CustomServerPublisher] relies on to still produce a sensible message, with no real
 * network call.
 */
class ReadCustomServerResponseTest {

    private fun bodyStream(text: String): InputStream = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `a 202 with a real body is returned verbatim, status and text both`() {
        val message = "Activité « test 34 » reçue (badminton, 57 min). Import Strava en cours."
        val connection = FakeHttpURLConnection(202, bodyStream(message))

        val response = readCustomServerResponse(connection)

        assertEquals(202, response.statusCode)
        assertEquals(message, response.body)
    }

    @Test
    fun `a 200 is returned as a success too, distinct from a 202`() {
        val message = "Déjà reçue le 26.08.2026 à 19:06 sous le titre « test 34 ». Rien n'est renvoyé à Strava."
        val connection = FakeHttpURLConnection(200, bodyStream(message))

        val response = readCustomServerResponse(connection)

        assertEquals(200, response.statusCode)
        assertEquals(message, response.body)
    }

    @Test
    fun `a 2xx with an empty body returns an empty string, never null or a crash`() {
        val connection = FakeHttpURLConnection(202, bodyStream(""))

        val response = readCustomServerResponse(connection)

        assertEquals("", response.body)
    }

    @Test
    fun `a non-2xx status throws with the response body attached`() {
        val connection = FakeHttpURLConnection(401, bodyStream("Jeton refusé."))

        val exception = try {
            readCustomServerResponse(connection)
            null
        } catch (e: CustomServerHttpException) {
            e
        }

        assertEquals(401, exception?.statusCode)
        assertEquals("Jeton refusé.", exception?.body)
    }

    @Test
    fun `a body that fails to read as text falls back to an empty one, on a 2xx response`() {
        val breakingStream = object : InputStream() {
            override fun read(): Int = throw IOException("stream broke mid-read")
        }
        val connection = FakeHttpURLConnection(202, breakingStream)

        val response = readCustomServerResponse(connection)

        assertEquals(202, response.statusCode)
        assertEquals("", response.body)
    }
}
