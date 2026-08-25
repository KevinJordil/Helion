package ch.kevinjordil.helion.source

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Thrown by [ExportLocation.copyToCache] when a location *is* configured but the file it
 * points to can no longer be read -- e.g. it was moved or deleted in the other app, or the
 * persisted URI permission was lost (this can happen across a reboot if it was never made
 * persistable in the first place). Deliberately distinct from the plain `null` returned when
 * nothing has been configured yet: those two situations need different messages in the UI,
 * so callers must not be able to conflate them.
 */
class ExportUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Remembers the Gadgetbridge export file chosen by the user through the Storage Access
 * Framework, and copies it into our cache before reading. SQLite cannot open a content://
 * URI, so a copy is unavoidable.
 *
 * Persisted URI permission contract: a plain OpenDocument result only grants access to the
 * URI for the current process lifetime. Surviving a reboot requires the *caller* to have
 * called `context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)`
 * with the flags from the original picker result *before* assigning [uri] here. This class
 * has no access to those flags at assignment time and therefore cannot take the permission
 * itself -- it only stores the string. The picker UI (Task 8) is responsible for taking the
 * permission; this class only reads what it is given.
 */
class ExportLocation(private val context: Context) {

    private val prefs = context.getSharedPreferences("helion", Context.MODE_PRIVATE)

    var uri: String?
        get() = prefs.getString(KEY_URI, null)
        set(value) = prefs.edit().putString(KEY_URI, value).apply()

    /** Size and modification time of the source file as of the last successful copy. */
    private var knownSize: Long
        get() = prefs.getLong(KEY_SIZE, NO_STAMP)
        set(value) = prefs.edit().putLong(KEY_SIZE, value).apply()

    private var knownModified: Long
        get() = prefs.getLong(KEY_MODIFIED, NO_STAMP)
        set(value) = prefs.edit().putLong(KEY_MODIFIED, value).apply()

    /**
     * Returns the path of a readable copy, or null if no location has been configured yet.
     *
     * With no way to trigger a Gadgetbridge refresh, most passes read a source file that has
     * not changed since the last one -- as often as every 30 minutes, forever, on a phone
     * whose Gadgetbridge cannot be triggered. The file only grows (hundreds of KB per day),
     * so re-copying it unconditionally every pass is real, needless I/O. When the source's
     * size and modification time both match what was seen at the last successful copy, and
     * the previous cached copy is still there, this returns that cached path without
     * touching either file. Any mismatch -- size, modification time, or a missing cache --
     * copies again; when the source's stamp cannot be determined at all, it also copies
     * again rather than risk skipping a real change.
     *
     * The copy is staged in a temporary file and moved into place with a single rename, so
     * the cached export is only ever a complete file. Writing straight into it would leave a
     * truncated database on disk for the duration of every copy -- and the periodic worker
     * and a manual "Sync now" can be triggered at the same moment, which is exactly when a
     * reader would meet a half-written file. A rename is atomic within one directory, and a
     * reader that already has the previous copy open keeps reading it to the end.
     *
     * @throws ExportUnavailableException if a location *is* configured but the underlying
     * file can no longer be opened (moved, deleted, or the permission was lost). Thrown
     * rather than returned as null so callers -- and eventually the UI -- can tell "nothing
     * configured" apart from "configured but broken". A copy that fails halfway leaves the
     * previously cached export untouched rather than replacing it with a partial one.
     */
    fun copyToCache(): String? {
        val source = uri ?: return null
        val sourceUri = Uri.parse(source)
        val destination = File(context.cacheDir, "gadgetbridge-export.db")

        val stamp = sourceStamp(sourceUri)
        if (stamp != null &&
            stamp.size == knownSize &&
            stamp.lastModified == knownModified &&
            destination.exists()
        ) {
            return destination.absolutePath
        }

        val staging = File.createTempFile("gadgetbridge-export", ".part", context.cacheDir)
        try {
            val opened = context.contentResolver.openInputStream(sourceUri)
                ?: throw ExportUnavailableException("No stream for export location: $source")
            opened.use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            }
            if (!staging.renameTo(destination)) {
                throw ExportUnavailableException("Failed to move the copied export into place")
            }
        } catch (e: ExportUnavailableException) {
            throw e
        } catch (e: FileNotFoundException) {
            throw ExportUnavailableException("Export location is no longer readable: $source", e)
        } catch (e: SecurityException) {
            throw ExportUnavailableException("Lost permission to read the export location: $source", e)
        } catch (e: IOException) {
            throw ExportUnavailableException("Failed to copy the export from: $source", e)
        } finally {
            // A no-op once the rename succeeded; on any failure path it clears the partial
            // file so the cache does not accumulate one per failed pass.
            staging.delete()
        }
        if (stamp != null) {
            knownSize = stamp.size
            knownModified = stamp.lastModified
        }
        return destination.absolutePath
    }

    /**
     * Size and modification time of [uri], or null when either could not be determined --
     * e.g. the document does not exist, or its provider does not report one of the two
     * columns. Never throws: a stat failure here just means "copy again to be safe", the
     * actual read failure (if any) is left to surface from the real copy attempt below with
     * its established, tested error messages.
     */
    private fun sourceStamp(uri: Uri): SourceStamp? = try {
        if (uri.scheme == "file") {
            val file = uri.path?.let(::File)
            if (file != null && file.exists()) SourceStamp(file.length(), file.lastModified()) else null
        } else {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (sizeIndex < 0 || modifiedIndex < 0 || cursor.isNull(sizeIndex) || cursor.isNull(modifiedIndex)) {
                    null
                } else {
                    SourceStamp(cursor.getLong(sizeIndex), cursor.getLong(modifiedIndex))
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    private data class SourceStamp(val size: Long, val lastModified: Long)

    private companion object {
        const val KEY_URI = "export_uri"
        const val KEY_SIZE = "export_size"
        const val KEY_MODIFIED = "export_modified"
        const val NO_STAMP = -1L
    }
}
