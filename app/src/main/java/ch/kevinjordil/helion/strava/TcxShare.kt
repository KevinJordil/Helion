package ch.kevinjordil.helion.strava

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.store.MinuteSample
import java.io.File

/**
 * Transport B: writes the same TCX [writeTcx] produces to a cache file and hands it to the
 * Android share sheet, so the owner can send it wherever he likes or import it into Strava
 * himself. This is the insurance the module's brief calls for -- it costs almost nothing
 * beyond transport A because the file exists either way, and it keeps working even if
 * transport A stops (no subscription, expired authorisation, missing credentials).
 *
 * The file lives under the app's cache dir, in the `tcx/` subdirectory `file_paths.xml`
 * exposes through the FileProvider -- nothing else in the app's storage is reachable this
 * way. `FLAG_GRANT_READ_URI_PERMISSION` is required on the intent because the receiving
 * app has no other route to a `content://` URI it does not own.
 */
fun buildShareIntent(context: Context, activity: Activity, samples: List<MinuteSample>): Intent {
    val tcx = writeTcx(activity.sport, activity.startTimestamp, activity.endTimestamp, samples)
    val dir = File(context.cacheDir, "tcx").apply { mkdirs() }
    val file = File(dir, "helion-activity-${activity.id}.tcx")
    file.writeText(tcx, Charsets.UTF_8)

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.garmin.tcx+xml"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(sendIntent, null)
}
