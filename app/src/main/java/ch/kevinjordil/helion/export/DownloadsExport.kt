package ch.kevinjordil.helion.export

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.store.SportType
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DOWNLOAD_FILE_NAME_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")

/**
 * `<sport>-<start date>-<start time>.tcx`, e.g. `badminton-2026-08-26-2010.tcx` -- what the
 * save action names the file it drops in Downloads, so the owner can recognise which
 * activity it is at a glance without opening it. Built only from a fixed lower-case slug
 * table ([downloadSportSlug], never a translated display label) and digits/hyphens, so it
 * is always a safe filename regardless of device locale or the filesystem Downloads lives
 * on. Minute-resolution on purpose: that already matches this strap's own recording
 * resolution, so two activities only ever share a name when they genuinely started in the
 * same sport in the same minute -- the case [uniqueDownloadFileName] resolves.
 */
fun tcxDownloadFileName(sport: SportType, startTimestamp: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = DOWNLOAD_FILE_NAME_TIME_FORMAT.format(Instant.ofEpochSecond(startTimestamp).atZone(zone))
    return "${downloadSportSlug(sport)}-$time.tcx"
}

private fun downloadSportSlug(sport: SportType): String = when (sport) {
    SportType.BADMINTON -> "badminton"
    SportType.RUNNING -> "course"
    SportType.CYCLING -> "velo"
    SportType.WALKING -> "marche"
    SportType.SWIMMING -> "natation"
    SportType.OTHER -> "activite"
}

/**
 * [baseName] unchanged if it is not already in [existingNames]; otherwise the smallest
 * `-2`, `-3`, ... suffix inserted before the extension that is not already taken. This is
 * the entirety of Helion's collision handling for two activities whose [tcxDownloadFileName]
 * would otherwise collide (same sport, same start minute): the caller queries Downloads for
 * [existingNames] once, right before writing, so this stays a pure, easily tested function
 * with no filesystem or MediaStore access of its own.
 */
fun uniqueDownloadFileName(baseName: String, existingNames: Set<String>): String {
    if (baseName !in existingNames) return baseName
    val dot = baseName.lastIndexOf('.')
    val stem = if (dot >= 0) baseName.substring(0, dot) else baseName
    val extension = if (dot >= 0) baseName.substring(dot) else ""
    var counter = 2
    while ("$stem-$counter$extension" in existingNames) counter++
    return "$stem-$counter$extension"
}

/** What [saveTcxToDownloads] reports back to the activity detail screen. */
sealed class DownloadsSaveResult {
    /** Written under [fileName] -- what the confirmation shows the owner. */
    data class Saved(val fileName: String) : DownloadsSaveResult()

    /**
     * Only possible on API 26-28 (see the manifest's own comment on
     * `WRITE_EXTERNAL_STORAGE`): the caller should ask for that permission and retry.
     */
    object PermissionRequired : DownloadsSaveResult()

    data class Failed(val message: String) : DownloadsSaveResult()
}

/**
 * Writes [activity]'s TCX straight into the device's Downloads folder -- no share sheet,
 * no destination picker. On API 29+ this goes through `MediaStore.Downloads`, which needs
 * no storage permission at all; on API 26-28, before that collection existed, it falls back
 * to a plain file in the public Downloads directory, which does need
 * `WRITE_EXTERNAL_STORAGE` (declared `maxSdkVersion="28"` in the manifest, since it is
 * never needed, and never requested, above that).
 */
fun saveTcxToDownloads(
    context: Context,
    activity: Activity,
    samples: List<MinuteSample>,
    calories: Int?,
    zone: ZoneId = ZoneId.systemDefault(),
): DownloadsSaveResult {
    val tcx = writeTcx(activity.sport, activity.startTimestamp, activity.endTimestamp, samples, calories)
    val baseName = tcxDownloadFileName(activity.sport, activity.startTimestamp, zone)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        saveViaMediaStore(context, baseName, tcx)
    } else {
        saveViaLegacyFile(context, baseName, tcx)
    }
}

private fun saveViaMediaStore(context: Context, baseName: String, tcx: String): DownloadsSaveResult = try {
    val resolver = context.contentResolver
    val fileName = uniqueDownloadFileName(baseName, queryExistingDownloadNames(resolver))
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, "application/vnd.garmin.tcx+xml")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: return DownloadsSaveResult.Failed("MediaStore refused the insert")
    resolver.openOutputStream(uri)?.use { it.write(tcx.toByteArray(Charsets.UTF_8)) }
    values.clear()
    values.put(MediaStore.Downloads.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    DownloadsSaveResult.Saved(fileName)
} catch (e: Exception) {
    DownloadsSaveResult.Failed(e.message ?: "unknown error")
}

private fun queryExistingDownloadNames(resolver: ContentResolver): Set<String> {
    val names = mutableSetOf<String>()
    resolver.query(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Downloads.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
        while (cursor.moveToNext()) names.add(cursor.getString(nameIndex))
    }
    return names
}

private fun saveViaLegacyFile(context: Context, baseName: String, tcx: String): DownloadsSaveResult {
    if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return DownloadsSaveResult.PermissionRequired
    }
    return try {
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        val fileName = uniqueDownloadFileName(baseName, dir.list()?.toSet() ?: emptySet())
        File(dir, fileName).writeText(tcx, Charsets.UTF_8)
        DownloadsSaveResult.Saved(fileName)
    } catch (e: Exception) {
        DownloadsSaveResult.Failed(e.message ?: "unknown error")
    }
}

/**
 * Opens Strava's web uploader, which is where an existing file can actually be imported.
 * Deliberately not the Strava Android app: it records activities and syncs devices, but
 * does not import a file, so sending the owner there would leave him holding a .tcx with
 * nowhere to put it. The web page accepts TCX on a free account.
 */
fun buildOpenStravaIntent(): Intent =
    // Straight to the web uploader, not the Strava app: the Android app records and syncs
    // devices but does not import an existing file, so launching it would leave the owner
    // holding a .tcx with nowhere to put it. The browser page accepts TCX on a free account.
    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.strava.com/upload/select"))

