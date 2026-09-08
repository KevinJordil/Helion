package ch.kevinjordil.helion.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LAST_BACKGROUND_SYNC_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())

/**
 * Where the Gadgetbridge export file is chosen, and where a sync against it can be
 * triggered by hand -- Réglages' [SettingsSection.SOURCE] entry. Unchanged from before this
 * screen was split out of one long scroll: same file picker, same persistable-permission
 * handling (see the picker callback's own comment below), same manual sync action and
 * result message.
 *
 * Also shows when the *background* worker itself last ran (see
 * [ch.kevinjordil.helion.store.SyncState.lastBackgroundSyncAttempt]'s kdoc for why that is
 * a different fact from "a sync ran recently" -- opening the app runs one every time) and,
 * since the one thing this app cannot do anything about from inside itself is a phone that
 * refuses to schedule it, a direct way to reach the per-app battery setting that -- on this
 * owner's Samsung phone in particular -- is what silently starves it: "Mise en veille" or
 * "Mise en veille profonde" put an infrequently-opened app's background work to sleep by
 * default. [Settings.ACTION_APPLICATION_DETAILS_SETTINGS] is the one battery-related screen
 * every Android build exposes through a stable, documented action; the OEM-specific
 * "sleeping apps" list Samsung actually uses has no public entry point at all, so pointing at
 * the app's own settings page -- one tap further to the battery section from there -- is the
 * closest this code can get the owner without guessing at a vendor-specific Intent that the
 * next One UI release could rename or remove outright.
 */
@Composable
fun SourceSettingsSection(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = HelionThemeTokens.colors

    var locationUri by remember { mutableStateOf(container.exportLocation.uri) }
    var syncing by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<Pair<Int, List<Any>>?>(null) }
    var pickRefused by remember { mutableStateOf(false) }
    var lastBackgroundSync by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        lastBackgroundSync = container.database.syncState().get()?.lastBackgroundSyncAttempt
    }

    val pickExportFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // MANDATORY: a plain OpenDocument result only grants read access for this
            // process's lifetime. Without taking a persistable permission here, before
            // the URI is stored, the app would work fine right now and then silently
            // stop being able to read the file after the next reboot. ExportLocation
            // cannot take this permission itself -- it only has the string, not the
            // original result's flags -- so it is documented as this layer's job.
            //
            // The call can throw SecurityException when the picked file's provider does
            // not support persistable grants at all -- resolveExportPick keeps that from
            // crashing the app right here, at the exact moment the user picks a file.
            when (
                resolveExportPick {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            ) {
                ExportPickOutcome.Granted -> {
                    container.exportLocation.uri = uri.toString()
                    locationUri = uri.toString()
                    pickRefused = false
                }
                ExportPickOutcome.Refused -> {
                    // Not stored: see resolveExportPick's kdoc for why.
                    pickRefused = true
                }
            }
        }
    }

    SettingsFieldLabel(stringResource(R.string.export_location_label))
    Text(
        locationUri?.let { stringResource(R.string.export_location_set, it) }
            ?: stringResource(R.string.export_location_none),
        style = HelionType.bodySmall,
        color = colors.textSecondary,
    )
    Button(onClick = { pickExportFile.launch(arrayOf("*/*")) }) {
        Text(stringResource(R.string.choose_export_file))
    }
    if (pickRefused) {
        SettingsWarning(stringResource(R.string.export_location_grant_refused))
    }

    Button(
        enabled = !syncing,
        onClick = {
            syncing = true
            resultMessage = null
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runSync(
                        copyToCache = { container.exportLocation.copyToCache() },
                        // A manual tap always attempts to trigger a refresh, backoff or
                        // not: the user is actively waiting for the result, and this is
                        // also how they find out the moment triggering starts working
                        // again (a Gadgetbridge update, enabling the Intent API).
                        ingest = { path -> container.ingestor.ingest(path, force = true) },
                    )
                }
                resultMessage = syncMessage(outcome)
                syncing = false
            }
        },
    ) {
        Text(stringResource(if (syncing) R.string.syncing else R.string.sync_now))
    }

    resultMessage?.let { (resId, args) ->
        Text(stringResource(resId, *args.toTypedArray()), style = HelionType.bodySmall, color = colors.textSecondary)
    }

    Text(
        lastBackgroundSync?.let {
            stringResource(R.string.source_last_background_sync, LAST_BACKGROUND_SYNC_FORMAT.format(Instant.ofEpochSecond(it)))
        } ?: stringResource(R.string.source_last_background_sync_never),
        style = HelionType.bodySmall,
        color = colors.textTertiary,
    )
    Text(stringResource(R.string.source_background_sync_battery_hint), style = HelionType.bodySmall, color = colors.textSecondary)
    Button(
        onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                },
            )
        },
    ) {
        Text(stringResource(R.string.source_open_battery_settings))
    }
}
