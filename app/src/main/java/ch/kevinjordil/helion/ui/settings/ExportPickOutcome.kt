package ch.kevinjordil.helion.ui.settings

/**
 * Outcome of trying to take a persistable read grant on a just-picked export URI.
 *
 * `takePersistableUriPermission` throws [SecurityException] when the content provider behind
 * the picked URI does not support persistable grants at all. A Gadgetbridge export is
 * normally a plain local file, whose provider does support this, but Helion has no way to
 * know a given provider's capability in advance -- the call has to be attempted and its
 * failure handled, not assumed away.
 */
sealed interface ExportPickOutcome {
    data object Granted : ExportPickOutcome
    data object Refused : ExportPickOutcome
}

/**
 * Wraps the persistable-permission side effect so a [SecurityException] from it can never
 * escape into the activity-result callback and crash the app at the exact moment the user
 * picks a file. [takePermission] is expected to call
 * `contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)`; it is
 * taken as a plain function here, not a Uri/ContentResolver, so this stays testable without
 * Compose, Robolectric, or a real content provider.
 *
 * The caller decides what happens next. Helion's choice (see [SettingsScreen]): on
 * [ExportPickOutcome.Refused], do not store the URI. Storing it would give an app that works
 * until the next reboot revokes the temporary grant and then fails in a way nobody can
 * diagnose -- exactly the bug this permission check exists to prevent.
 */
fun resolveExportPick(takePermission: () -> Unit): ExportPickOutcome = try {
    takePermission()
    ExportPickOutcome.Granted
} catch (e: SecurityException) {
    ExportPickOutcome.Refused
}
