package ch.kevinjordil.helion.customserver

import ch.kevinjordil.helion.export.writeFileField
import ch.kevinjordil.helion.export.writeFormField
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * The real network implementation of [CustomServerApi], built on plain [HttpURLConnection]
 * -- no new dependency.
 */
class HttpCustomServerApi : CustomServerApi {

    override fun send(serverUrl: String, token: String, request: CustomServerSendRequest): CustomServerResponse {
        val boundary = "helion-${UUID.randomUUID()}"
        val body = buildCustomServerMultipartBody(boundary, request)

        val connection = (URL(serverUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            connection.outputStream.use { it.write(body) }
            return readCustomServerResponse(connection)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Builds the exact `multipart/form-data` body `HttpCustomServerApi.send` submits -- pulled
 * out as its own pure function so the request shape (every field present, `calories`
 * genuinely absent rather than sent as zero when [CustomServerSendRequest.calories] is
 * null, the file field's name and content) can be verified in a test with no
 * [java.net.HttpURLConnection] and no real network call at all. Field-writing itself reuses
 * [ch.kevinjordil.helion.export.writeFormField]/[ch.kevinjordil.helion.export.writeFileField].
 */
internal fun buildCustomServerMultipartBody(boundary: String, request: CustomServerSendRequest): ByteArray =
    ByteArrayOutputStream().apply {
        writeFormField(boundary, "sport", request.sport)
        writeFormField(boundary, "title", request.title)
        writeFormField(boundary, "description", request.description)
        writeFormField(boundary, "start", request.startIso)
        writeFormField(boundary, "duration_seconds", request.durationSeconds.toString())
        // Omitted entirely when unknown -- never sent as a literal "0", which would read as
        // a real, if tiny, calorie count instead of "not computed".
        request.calories?.let { writeFormField(boundary, "calories", it.toString()) }
        writeFormField(boundary, "external_id", request.externalId)
        writeFileField(boundary, "file", request.fileName, "application/vnd.garmin.tcx+xml", request.tcx.toByteArray(Charsets.UTF_8))
        write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
    }.toByteArray()

/**
 * Reads the response for a just-submitted request, throwing [CustomServerHttpException] on
 * a non-2xx status with the response body attached -- never swallowing the real reason.
 *
 * Reading the body is itself wrapped: an unreachable host or a dropped connection is a
 * transport failure that already surfaces as a thrown [java.io.IOException] before this is
 * ever called, but the body of an otherwise-received response can still fail to read as
 * text (a stream that breaks mid-read, say) -- that is not a transport failure, so it is
 * treated as "no message" (an empty body) rather than propagating, leaving
 * [CustomServerPublisher] to fall back to its own wording exactly as it does for a
 * genuinely empty body.
 */
internal fun readCustomServerResponse(connection: HttpURLConnection): CustomServerResponse {
    val code = connection.responseCode
    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
    val text = try {
        stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    } catch (e: IOException) {
        ""
    }
    if (code !in 200..299) {
        throw CustomServerHttpException(code, text)
    }
    return CustomServerResponse(code, text)
}
