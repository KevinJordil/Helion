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
import androidx.compose.material3.MaterialTheme
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
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            container.exportLocation.uri = uri.toString()
            locationUri = uri.toString()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineSmall)

        Text(stringResource(R.string.export_location_label), style = MaterialTheme.typography.titleMedium)
        Text(
            locationUri?.let { stringResource(R.string.export_location_set, it) }
                ?: stringResource(R.string.export_location_none),
        )
        Button(onClick = { pickExportFile.launch(arrayOf("*/*")) }) {
            Text(stringResource(R.string.choose_export_file))
        }

        HorizontalDivider()

        Button(
            enabled = !syncing,
            onClick = {
                syncing = true
                resultMessage = null
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        runSync(
                            copyToCache = { container.exportLocation.copyToCache() },
                            ingest = { path -> container.ingestor.ingest(path) },
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
            Text(stringResource(resId, *args.toTypedArray()))
        }
    }
}
