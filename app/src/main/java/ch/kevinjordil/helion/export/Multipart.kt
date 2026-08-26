package ch.kevinjordil.helion.export

import java.io.ByteArrayOutputStream

/**
 * The two building blocks every `multipart/form-data` body in this app is assembled from,
 * used by [ch.kevinjordil.helion.customserver.HttpCustomServerApi] for the custom-server
 * export. `internal` rather than `private` since the call site lives in this Gradle
 * module, just in a different package.
 */
internal fun ByteArrayOutputStream.writeFormField(boundary: String, name: String, value: String) {
    write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
    write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
    write("$value\r\n".toByteArray(Charsets.UTF_8))
}

internal fun ByteArrayOutputStream.writeFileField(
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
