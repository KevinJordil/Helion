package ch.kevinjordil.helion.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType

/**
 * The on/off switch for candidate-detection notifications, plus the moment Android 13's
 * `POST_NOTIFICATIONS` permission is actually asked for: only when the owner ticks this
 * checkbox on, not at first launch, so the French rationale text above it is read in the
 * context it explains rather than a generic first-run dialog -- Réglages'
 * [SettingsSection.NOTIFICATIONS] entry, unchanged from before this screen was split out of
 * one long scroll.
 *
 * Declining leaves [NotificationPreference.enabled] on -- candidates simply keep appearing
 * in Activités, silently, exactly as [ch.kevinjordil.helion.notification.CandidateNotifier]'s
 * own kdoc describes -- with [permissionDenied] surfacing that explanation right here instead
 * of leaving the owner wondering why nothing showed up.
 */
@Composable
fun NotificationsSettingsSection(container: AppContainer) {
    val context = LocalContext.current
    val colors = HelionThemeTokens.colors

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    var enabled by remember { mutableStateOf(container.notificationPreference.enabled) }
    var permissionDenied by remember { mutableStateOf(false) }

    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // Refusing the permission never turns the setting back off: it is still what the
        // owner asked for, and Android may offer the prompt again later (e.g. after the
        // owner clears the "don't ask again" state from system settings). Only the
        // permission itself gates whether a notification actually posts -- see
        // ch.kevinjordil.helion.notification.CandidateNotifier.
        permissionDenied = !granted
    }

    Text(stringResource(R.string.notification_settings_explanation), style = HelionType.bodySmall, color = colors.textSecondary)

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(
            checked = enabled,
            onCheckedChange = { checked ->
                enabled = checked
                container.notificationPreference.enabled = checked
                if (checked && !hasPermission()) {
                    requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
        Text(stringResource(R.string.notification_settings_toggle_label), style = HelionType.bodySmall, color = colors.textSecondary)
    }

    if (permissionDenied) {
        SettingsWarning(stringResource(R.string.notification_settings_permission_denied))
    }
}
