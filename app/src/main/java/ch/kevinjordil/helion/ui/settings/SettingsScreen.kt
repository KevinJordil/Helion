package ch.kevinjordil.helion.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where the Gadgetbridge export file is chosen and where a sync can be triggered by hand.
 */
@Composable
fun SettingsScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var locationUri by remember { mutableStateOf(container.exportLocation.uri) }
    var syncing by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<Pair<Int, List<Any>>?>(null) }
    var pickRefused by remember { mutableStateOf(false) }

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

    val colors = HelionThemeTokens.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tab_settings).uppercase(), style = HelionType.label, color = colors.textSecondary)

        Text(stringResource(R.string.export_location_label), style = HelionType.body, color = colors.textPrimary)
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
            Text(stringResource(R.string.export_location_grant_refused), style = HelionType.bodySmall, color = colors.accentAmber)
        }

        HorizontalDivider(color = colors.divider)

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
    }
}
