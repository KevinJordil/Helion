package ch.kevinjordil.helion.strava

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONObject

/** The real network implementation of [StravaApi], built on plain [HttpURLConnection]. */
class HttpStravaApi : StravaApi {

    override fun createUpload(
        accessToken: String,
        tcx: String,
        sportType: String,
        name: String,
        externalId: String,
    ): UploadCreated {
        val boundary = "helion-${UUID.randomUUID()}"
        val body = ByteArrayOutputStream().apply {
            writeFormField(boundary, "data_type", "tcx")
            writeFormField(boundary, "sport_type", sportType)
            writeFormField(boundary, "name", name)
            writeFormField(boundary, "external_id", externalId)
            writeFileField(boundary, "file", "activity.tcx", "application/octet-stream", tcx.toByteArray(Charsets.UTF_8))
            write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        }.toByteArray()

        val connection = (URL("https://www.strava.com/api/v3/uploads").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connectTimeout = 15_000
            readTimeout = 60_000
        }
        val text = try {
            connection.outputStream.use { it.write(body) }
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
        val json = JSONObject(text)
        return UploadCreated(uploadId = json.get("id").toString())
    }

    override fun pollUpload(accessToken: String, uploadId: String): UploadStatus {
        val connection = (URL("https://www.strava.com/api/v3/uploads/$uploadId").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $accessToken")
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        val text = try {
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
        val json = JSONObject(text)
        val error = json.optString("error", "").takeIf { it.isNotBlank() && it != "null" }
        val activityId = json.optString("activity_id", "").takeIf { it.isNotBlank() && it != "null" }
        return when {
            activityId != null && error != null && error.contains("duplicate", ignoreCase = true) ->
                UploadStatus.Duplicate(activityId)
            activityId != null -> UploadStatus.Done(activityId)
            error != null -> UploadStatus.Errored(error)
            else -> UploadStatus.Processing
        }
    }

    override fun updateActivity(accessToken: String, remoteId: String, name: String, sportType: String) {
        val body = "name=${urlEncode(name)}&sport_type=${urlEncode(sportType)}"
        val connection = (URL("https://www.strava.com/api/v3/activities/$remoteId").openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }
}

private fun ByteArrayOutputStream.writeFormField(boundary: String, name: String, value: String) {
    write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
    write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
    write("$value\r\n".toByteArray(Charsets.UTF_8))
}

private fun ByteArrayOutputStream.writeFileField(
    boundary: String,
    fieldName: String,
    fileName: String,
    contentType: String,
    content: ByteArray,
) {
    write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
    write(
        "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"\r\n"
            .toByteArray(Charsets.UTF_8),
    )
    write("Content-Type: $contentType\r\n\r\n".toByteArray(Charsets.UTF_8))
    write(content)
    write("\r\n".toByteArray(Charsets.UTF_8))
}
