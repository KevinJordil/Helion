package ch.kevinjordil.helion.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
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
 * [NotificationPreference.enabled] defaults to `true` (see its own kdoc), which is exactly
 * the failure this section used to have: the checkbox rendered already checked on first
 * visit, [onCheckedChange] never fired because the owner never needed to tick anything, and
 * the permission request that lambda alone used to gate on was therefore never reached --
 * silently, forever, with nothing on screen to say so. [permissionGranted] is now read from
 * Android itself at composition time, not only after an explicit request, so [enabled] and
 * "the permission Android actually holds" are shown as the two independent facts they are;
 * whenever they disagree the warning and its own grant button are both here, regardless of
 * how that disagreement came about (never asked, asked and refused, or revoked later from
 * system settings).
 *
 * Declining -- or simply never granting -- leaves [NotificationPreference.enabled] on:
 * candidates simply keep appearing in Activités, silently, exactly as
 * [ch.kevinjordil.helion.notification.CandidateNotifier]'s own kdoc describes.
 */
@Composable
fun NotificationsSettingsSection(container: AppContainer) {
    val context = LocalContext.current
    val colors = HelionThemeTokens.colors

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    var enabled by remember { mutableStateOf(container.notificationPreference.enabled) }
    var permissionGranted by remember { mutableStateOf(hasPermission()) }

    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // Refusing the permission never turns the setting back off: it is still what the
        // owner asked for, and Android may offer the prompt again later (e.g. after the
        // owner clears the "don't ask again" state from system settings). Only the
        // permission itself gates whether a notification actually posts -- see
        // ch.kevinjordil.helion.notification.CandidateNotifier.
        permissionGranted = granted
    }

    Text(stringResource(R.string.notification_settings_explanation), style = HelionType.bodySmall, color = colors.textSecondary)

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(
            checked = enabled,
            onCheckedChange = { checked ->
                enabled = checked
                container.notificationPreference.enabled = checked
                if (checked && !permissionGranted) {
                    requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
        Text(stringResource(R.string.notification_settings_toggle_label), style = HelionType.bodySmall, color = colors.textSecondary)
    }

    if (notificationPermissionMissingWarning(enabled, permissionGranted)) {
        SettingsWarning(stringResource(R.string.notification_settings_permission_missing))
        Button(onClick = { requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
            Text(stringResource(R.string.notification_settings_grant_button))
        }
    }
}

/**
 * Whether Réglages should tell the owner that, despite the toggle being on, no notification
 * will actually post -- true exactly when [preferenceEnabled] but Android's own
 * `POST_NOTIFICATIONS` permission is not held. Kept as a plain function, not inlined into
 * the composable, so the one condition this whole fix hinges on is checkable without
 * Compose machinery at all.
 */
fun notificationPermissionMissingWarning(preferenceEnabled: Boolean, permissionGranted: Boolean): Boolean =
    preferenceEnabled && !permissionGranted
