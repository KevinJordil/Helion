package ch.kevinjordil.helion.ui.settings

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
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
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.notification.CandidateNotifier
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType

/**
 * The on/off switch for candidate-detection notifications, plus everything Réglages can
 * actually prove about whether one would arrive -- Réglages' [SettingsSection.NOTIFICATIONS]
 * entry, unchanged from before this screen was split out of one long scroll.
 *
 * [NotificationPreference.enabled] defaults to `true` (see its own kdoc), which is exactly
 * the failure this section used to have: the checkbox rendered already checked on first
 * visit, `onCheckedChange` never fired because the owner never needed to tick anything, and
 * the permission request that lambda alone used to gate on was therefore never reached --
 * silently, forever, with nothing on screen to say so.
 *
 * That original gap turned out to be one of three, each independent, each just as silent:
 * the runtime `POST_NOTIFICATIONS` permission (Android 13+), notifications disabled for the
 * whole app in system settings, and the candidate-detection channel itself disabled or set
 * to an importance that shows nothing. [notificationDiagnosticsState] reads Android's own
 * three answers to those questions -- `checkSelfPermission`,
 * `NotificationManagerCompat.areNotificationsEnabled()`, and the channel's actual
 * [NotificationManager.getNotificationChannel] importance -- and turns them into exactly
 * one of [NotificationDiagnosticsState], never a single vague "notifications off". Each
 * failing state gets its own fix action, opening the one Android screen that actually
 * addresses it: the runtime prompt while it can still be asked for, the app's own
 * notification settings once it cannot (denied permanently, or the app disabled outright),
 * and the channel's own settings when the channel itself is what is wrong.
 * [NotificationDiagnosticsState.ALL_CLEAR] is said plainly, not left implicit, so a screen
 * that checked and found nothing wrong reads differently from a screen that has not
 * rechecked yet.
 *
 * [LifecycleEventEffect] re-runs every one of those three checks on every `ON_RESUME`, not
 * only on first composition (see [ch.kevinjordil.helion.ui.home.HomeScreen]'s own use of the
 * same effect for why `LaunchedEffect(Unit)` alone would miss this): fixing any of these
 * states means leaving this screen for an Android settings screen and coming back, and a
 * stale "off" read on return would be worse than useless.
 *
 * "Send a test notification" posts through [CandidateNotifier.sendTestNotification] --
 * [AppContainer.candidateNotifier], the exact same instance and channel a real candidate
 * notification uses -- so a successful test proves the real path end to end, not a
 * synthetic stand-in.
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
    fun appNotificationsEnabled() = NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun channelBlocksNotifications(): Boolean {
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CandidateNotifier.CHANNEL_ID)
        // No channel yet (never posted once) is not a failure: Android will create it at
        // its own default importance -- see CandidateNotifier.ensureChannel -- the moment a
        // notification, real or test, is first posted.
        return channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE
    }
    // `shouldShowRequestPermissionRationale` alone cannot tell "never asked" apart from
    // "denied permanently" -- both answer false. NotificationPreference.permissionRequested
    // is what remembers which one this actually is.
    fun canRequestPermission(): Boolean {
        if (!container.notificationPreference.permissionRequested) return true
        val activity = context as? Activity ?: return true
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
    }

    var enabled by remember { mutableStateOf(container.notificationPreference.enabled) }
    var permissionGranted by remember { mutableStateOf(hasPermission()) }
    var appEnabled by remember { mutableStateOf(appNotificationsEnabled()) }
    var channelBlocked by remember { mutableStateOf(channelBlocksNotifications()) }
    var requestablePermission by remember { mutableStateOf(canRequestPermission()) }
    var intentUnavailable by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Boolean?>(null) }

    fun refreshDiagnostics() {
        permissionGranted = hasPermission()
        appEnabled = appNotificationsEnabled()
        channelBlocked = channelBlocksNotifications()
        requestablePermission = canRequestPermission()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshDiagnostics() }

    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // Refusing the permission never turns the setting back off: it is still what the
        // owner asked for, and Android may offer the prompt again later (e.g. after the
        // owner clears the "don't ask again" state from system settings). Only the
        // permission itself gates whether a notification actually posts -- see
        // ch.kevinjordil.helion.notification.CandidateNotifier.
        permissionGranted = granted
        requestablePermission = canRequestPermission()
    }

    fun launchRequestPermission() {
        container.notificationPreference.permissionRequested = true
        requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun openIntent(intent: Intent) {
        intentUnavailable = try {
            if (intent.resolveActivity(context.packageManager) == null) {
                true
            } else {
                context.startActivity(intent)
                false
            }
        } catch (e: ActivityNotFoundException) {
            true
        }
    }

    fun openAppNotificationSettings() = openIntent(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )

    fun openChannelSettings() = openIntent(
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, CandidateNotifier.CHANNEL_ID),
    )

    Text(stringResource(R.string.notification_settings_explanation), style = HelionType.bodySmall, color = colors.textSecondary)

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(
            checked = enabled,
            onCheckedChange = { checked ->
                enabled = checked
                container.notificationPreference.enabled = checked
                if (checked && !permissionGranted) {
                    launchRequestPermission()
                }
            },
        )
        Text(stringResource(R.string.notification_settings_toggle_label), style = HelionType.bodySmall, color = colors.textSecondary)
    }

    val diagnosticsState = notificationDiagnosticsState(
        preferenceEnabled = enabled,
        permissionGranted = permissionGranted,
        canRequestPermission = requestablePermission,
        appNotificationsEnabled = appEnabled,
        channelBlocksNotifications = channelBlocked,
    )

    diagnosticsState?.let { state ->
        if (state == NotificationDiagnosticsState.ALL_CLEAR) {
            Text(stringResource(R.string.notification_settings_all_clear), style = HelionType.bodySmall, color = colors.textSecondary)
        } else {
            SettingsWarning(stringResource(diagnosticsMessageRes(state)))
            Button(
                onClick = {
                    testResult = null
                    when (state) {
                        NotificationDiagnosticsState.PERMISSION_MISSING -> launchRequestPermission()
                        NotificationDiagnosticsState.PERMISSION_DENIED, NotificationDiagnosticsState.APP_DISABLED -> openAppNotificationSettings()
                        NotificationDiagnosticsState.CHANNEL_DISABLED -> openChannelSettings()
                        NotificationDiagnosticsState.ALL_CLEAR -> Unit
                    }
                },
            ) {
                Text(stringResource(diagnosticsActionLabelRes(state)))
            }
            if (intentUnavailable) {
                SettingsWarning(stringResource(R.string.notification_settings_intent_unavailable))
            }
        }

        Button(
            onClick = {
                testResult = container.candidateNotifier.sendTestNotification()
                refreshDiagnostics()
            },
        ) {
            Text(stringResource(R.string.notification_settings_test_button))
        }

        testResult?.let { sent ->
            val messageRes = if (sent) R.string.notification_settings_test_sent else R.string.notification_settings_test_failed
            Text(stringResource(messageRes), style = HelionType.bodySmall, color = colors.textSecondary)
        }
    }
}

/**
 * The one Réglages label for each [NotificationDiagnosticsState] failure -- kept as a plain
 * mapping, not inlined into the composable, so every state is guaranteed a message the
 * moment it is added here.
 */
private fun diagnosticsMessageRes(state: NotificationDiagnosticsState): Int = when (state) {
    NotificationDiagnosticsState.PERMISSION_MISSING -> R.string.notification_settings_permission_missing
    NotificationDiagnosticsState.PERMISSION_DENIED -> R.string.notification_settings_permission_denied
    NotificationDiagnosticsState.APP_DISABLED -> R.string.notification_settings_app_disabled
    NotificationDiagnosticsState.CHANNEL_DISABLED -> R.string.notification_settings_channel_disabled
    NotificationDiagnosticsState.ALL_CLEAR -> R.string.notification_settings_all_clear
}

/**
 * The fix-action button label paired with [diagnosticsMessageRes], one per failing state.
 * Never called for [NotificationDiagnosticsState.ALL_CLEAR] -- the composable's own `if`
 * routes that state to the plain "all clear" text instead, with no fix button at all.
 */
private fun diagnosticsActionLabelRes(state: NotificationDiagnosticsState): Int = when (state) {
    NotificationDiagnosticsState.PERMISSION_MISSING -> R.string.notification_settings_grant_button
    NotificationDiagnosticsState.PERMISSION_DENIED, NotificationDiagnosticsState.APP_DISABLED -> R.string.notification_settings_open_app_settings_button
    NotificationDiagnosticsState.CHANNEL_DISABLED -> R.string.notification_settings_open_channel_settings_button
    NotificationDiagnosticsState.ALL_CLEAR -> error("ALL_CLEAR has no fix action")
}

/**
 * Every state Réglages' notifications section can actually prove about whether a
 * notification would reach the owner, one platform answer at a time:
 * - [PERMISSION_MISSING]: `POST_NOTIFICATIONS` not granted, but still askable -- the
 *   runtime prompt is the fix.
 * - [PERMISSION_DENIED]: not granted, and no longer askable (denied permanently, or a
 *   pre-13 app-level disable that behaves the same way from here) -- only the app's own
 *   notification settings can fix this now.
 * - [APP_DISABLED]: the permission is granted, but
 *   `NotificationManagerCompat.areNotificationsEnabled()` still says no -- the whole app
 *   was turned off in system settings.
 * - [CHANNEL_DISABLED]: the app is allowed to notify at all, but the candidate-detection
 *   channel itself is disabled or hidden.
 * - [ALL_CLEAR]: every check passed -- said plainly, not left as the absence of a warning,
 *   so the owner can tell "checked, nothing wrong" apart from "not checked yet".
 */
enum class NotificationDiagnosticsState {
    PERMISSION_MISSING,
    PERMISSION_DENIED,
    APP_DISABLED,
    CHANNEL_DISABLED,
    ALL_CLEAR,
}

/**
 * Turns Android's own three answers -- the runtime permission, whether the app is allowed
 * to notify at all, and the candidate channel's own importance -- into exactly one
 * [NotificationDiagnosticsState], checked in the order that matches which fix actually
 * addresses which symptom: the permission first (it is what everything else depends on),
 * then the app-wide switch, then the channel. `null` when [preferenceEnabled] is off,
 * mirroring [ch.kevinjordil.helion.notification.CandidateNotifier]'s own gate: an owner who
 * chose not to be notified is not shown a diagnosis of a path he deliberately is not using.
 *
 * Kept as a plain function, not inlined into the composable, so this decision is checkable
 * without Compose or Android at all -- [canRequestPermission] is the one input that itself
 * needs Android (`ActivityCompat.shouldShowRequestPermissionRationale` plus
 * [NotificationPreference.permissionRequested]) to compute, resolved by the caller before
 * this function ever runs.
 */
fun notificationDiagnosticsState(
    preferenceEnabled: Boolean,
    permissionGranted: Boolean,
    canRequestPermission: Boolean,
    appNotificationsEnabled: Boolean,
    channelBlocksNotifications: Boolean,
): NotificationDiagnosticsState? {
    if (!preferenceEnabled) return null
    if (!permissionGranted) {
        return if (canRequestPermission) NotificationDiagnosticsState.PERMISSION_MISSING else NotificationDiagnosticsState.PERMISSION_DENIED
    }
    if (!appNotificationsEnabled) return NotificationDiagnosticsState.APP_DISABLED
    if (channelBlocksNotifications) return NotificationDiagnosticsState.CHANNEL_DISABLED
    return NotificationDiagnosticsState.ALL_CLEAR
}
