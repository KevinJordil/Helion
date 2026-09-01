package ch.kevinjordil.helion.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.health.connect.client.PermissionController
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.healthconnect.HEALTH_CONNECT_PERMISSIONS
import ch.kevinjordil.helion.healthconnect.HealthConnectAvailability
import ch.kevinjordil.helion.healthconnect.HealthConnectExportOutcome
import ch.kevinjordil.helion.healthconnect.healthConnectAvailability
import ch.kevinjordil.helion.healthconnect.realHealthConnectWriterOrNull
import ch.kevinjordil.helion.store.HealthConnectExportState
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The on/off switch for exporting to Health Connect, plus the moment its runtime write
 * permissions are actually requested: only when the owner ticks this on, right after the
 * French explanation of what gets written -- Réglages' [SettingsSection.HEALTH_CONNECT]
 * entry, unchanged from before this screen was split out of one long scroll.
 *
 * Refusing or later revoking any permission never turns [HealthConnectConfig.enabled] back
 * off on its own: the setting still says what the owner asked for, and
 * [ch.kevinjordil.helion.healthconnect.HealthConnectExporter] itself is what checks the real
 * permission state fresh on every pass and writes nothing when it is missing -- this section
 * only ever reflects that back, in [permissionGranted].
 */
@Composable
fun HealthConnectSettingsSection(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = HelionThemeTokens.colors
    val config = container.healthConnectConfig

    var enabled by remember { mutableStateOf(config.enabled) }
    var permissionGranted by remember { mutableStateOf<Boolean?>(null) }
    var exportState by remember { mutableStateOf<HealthConnectExportState?>(null) }
    var lastOutcomeMessage by remember { mutableStateOf<Pair<Int, List<Any>>?>(null) }
    var exporting by remember { mutableStateOf(false) }

    val availability = remember { healthConnectAvailability(context) }

    suspend fun refreshPermissionAndState() {
        permissionGranted = withContext(Dispatchers.IO) { realHealthConnectWriterOrNull(context)?.hasWritePermission() }
        exportState = withContext(Dispatchers.IO) { container.database.healthConnectExportState().get() }
    }

    LaunchedEffect(Unit) { refreshPermissionAndState() }

    val requestPermissions = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        permissionGranted = granted.containsAll(HEALTH_CONNECT_PERMISSIONS)
    }

    Text(stringResource(R.string.health_connect_explanation), style = HelionType.bodySmall, color = colors.textSecondary)

    when (availability) {
        HealthConnectAvailability.NotInstalled -> SettingsWarning(stringResource(R.string.health_connect_not_installed))
        HealthConnectAvailability.UpdateRequired -> SettingsWarning(stringResource(R.string.health_connect_update_required))
        HealthConnectAvailability.Available -> Unit
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(
            checked = enabled,
            enabled = availability == HealthConnectAvailability.Available,
            onCheckedChange = { checked ->
                enabled = checked
                config.enabled = checked
                if (checked && permissionGranted != true) {
                    requestPermissions.launch(HEALTH_CONNECT_PERMISSIONS)
                }
            },
        )
        Text(stringResource(R.string.health_connect_toggle_label), style = HelionType.bodySmall, color = colors.textSecondary)
    }

    if (enabled && availability == HealthConnectAvailability.Available && permissionGranted == false) {
        SettingsWarning(stringResource(R.string.health_connect_permission_missing))
    }

    Button(
        enabled = !exporting && availability == HealthConnectAvailability.Available,
        onClick = {
            exporting = true
            lastOutcomeMessage = null
            scope.launch {
                val outcome = withContext(Dispatchers.IO) { container.healthConnectExporter.export() }
                lastOutcomeMessage = healthConnectOutcomeMessage(outcome)
                if (outcome is HealthConnectExportOutcome.PermissionMissing) permissionGranted = false
                refreshPermissionAndState()
                exporting = false
            }
        },
    ) {
        Text(stringResource(if (exporting) R.string.health_connect_exporting else R.string.health_connect_export_now))
    }

    val displayedMessage = lastOutcomeMessage ?: healthConnectStateMessage(exportState)
    displayedMessage?.let { (resId, args) ->
        Text(stringResource(resId, *args.toTypedArray()), style = HelionType.bodySmall, color = colors.textSecondary)
    }
}
