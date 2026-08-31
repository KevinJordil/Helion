package ch.kevinjordil.helion

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes an uncaught exception to Downloads before the process dies.
 *
 * The owner cannot attach his phone to the machine this app is built on, so `adb logcat`
 * is not available to him and a launch crash is otherwise a black box: the app opens and
 * closes with nothing to read. This leaves a plain text file he can open with any file
 * manager. It never swallows the crash -- the previous handler still runs, so Android's
 * own reporting is unchanged.
 */
fun installCrashLog(context: Context) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching { writeCrash(context, thread, error) }
        previous?.uncaughtException(thread, error)
    }
}

private fun writeCrash(context: Context, thread: Thread, error: Throwable) {
    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
    val text = buildString {
        appendLine("Helion crash")
        appendLine("build: ${BuildConfig.BUILD_STAMP}")
        appendLine("time: $stamp")
        appendLine("android: ${Build.VERSION.SDK_INT}  device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("thread: ${thread.name}")
        appendLine()
        append(trace)
    }
    val name = "helion-crash.txt"

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        uri?.let { context.contentResolver.openOutputStream(it)?.use { out -> out.write(text.toByteArray()) } }
    } else {
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        File(dir, name).writeText(text)
    }
}
