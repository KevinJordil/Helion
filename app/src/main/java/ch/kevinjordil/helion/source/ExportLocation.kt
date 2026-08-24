package ch.kevinjordil.helion.source

import android.content.Context
import android.net.Uri
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

    /**
     * Returns the path of a readable copy, or null if no location has been configured yet.
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
        val destination = File(context.cacheDir, "gadgetbridge-export.db")
        val staging = File.createTempFile("gadgetbridge-export", ".part", context.cacheDir)
        try {
            val opened = context.contentResolver.openInputStream(Uri.parse(source))
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
        return destination.absolutePath
    }

    private companion object {
        const val KEY_URI = "export_uri"
    }
}
