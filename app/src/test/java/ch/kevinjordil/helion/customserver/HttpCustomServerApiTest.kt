package ch.kevinjordil.helion.customserver

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
