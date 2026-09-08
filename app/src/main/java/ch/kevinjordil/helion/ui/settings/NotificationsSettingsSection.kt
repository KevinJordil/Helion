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
import androidx.compose.foundation.layout.Column
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
import ch.kevinjordil.helion.notification.SleepNightNotifier
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType

/**
 * Réglages' [SettingsSection.NOTIFICATIONS] entry: the candidate-detection channel (unchanged
 * from before this screen was split out of one long scroll) and, alongside it, the morning
 * sleep-summary channel added later -- two independent toggles, each with its own
 * diagnostics, sharing this one screen and the platform-level checks common to both (see
 * [NotificationChannelSection]'s own kdoc for why splitting this shared machinery out was
 * the point: a second channel added by copy-pasting the first, rather than reusing its
 * diagnostics, is exactly how a silently-disabled channel could go unnoticed for weeks a
 * second time).
 *
 * [NotificationPreference.enabled] defaults to `true` (see its own kdoc), which is exactly
 * the failure this section used to have: the checkbox rendered already checked on first
 * visit, `onCheckedChange` never fired because the owner never needed to tick anything, and
 * the permission request that lambda alone used to gate on was therefore never reached --
 * silently, forever, with nothing on screen to say so.
 *
 * That original gap turned out to be one of three, each independent, each just as silent:
 * the runtime `POST_NOTIFICATIONS` permission (Android 13+, shared across every channel this
 * app has), notifications disabled for the whole app in system settings, and a given
 * channel itself disabled or set to an importance that shows nothing.
 * [notificationDiagnosticsState] reads Android's own three answers to those questions --
 * `checkSelfPermission`, `NotificationManagerCompat.areNotificationsEnabled()`, and the
 * channel's actual [NotificationManager.getNotificationChannel] importance -- and turns
 * them into exactly one of [NotificationDiagnosticsState], never a single vague
 * "notifications off". Each failing state gets its own fix action, opening the one Android
 * screen that actually addresses it: the runtime prompt while it can still be asked for,
 * the app's own notification settings once it cannot (denied permanently, or the app
 * disabled outright), and the channel's own settings when the channel itself is what is
 * wrong. [NotificationDiagnosticsState.ALL_CLEAR] is said plainly, not left implicit, so a
 * screen that checked and found nothing wrong reads differently from a screen that has not
 * rechecked yet.
 *
 * [LifecycleEventEffect] re-runs every one of those checks on every `ON_RESUME`, not only on
 * first composition (see [ch.kevinjordil.helion.ui.home.HomeScreen]'s own use of the same
 * effect for why `LaunchedEffect(Unit)` alone would miss this): fixing any of these states
 * means leaving this screen for an Android settings screen and coming back, and a stale
 * "off" read on return would be worse than useless.
 */
@Composable
fun NotificationsSettingsSection(container: AppContainer) {
    val context = LocalContext.current

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun appNotificationsEnabled() = NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun channelBlocksNotifications(channelId: String): Boolean {
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(channelId)
        // No channel yet (never posted once) is not a failure: Android will create it at
        // its own default importance -- see CandidateNotifier.ensureChannel / SleepNightNotifier's
        // own equivalent -- the moment a notification, real or test, is first posted.
        return channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE
    }
    // `shouldShowRequestPermissionRationale` alone cannot tell "never asked" apart from
    // "denied permanently" -- both answer false. NotificationPreference.permissionRequested
    // is what remembers which one this actually is, shared across both channels since the
    // permission itself is requested once for the whole app, not per channel.
    fun canRequestPermission(): Boolean {
        if (!container.notificationPreference.permissionRequested) return true
        val activity = context as? Activity ?: return true
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
    }

    var permissionGranted by remember { mutableStateOf(hasPermission()) }
    var appEnabled by remember { mutableStateOf(appNotificationsEnabled()) }
    var requestablePermission by remember { mutableStateOf(canRequestPermission()) }
    var candidateChannelBlocked by remember { mutableStateOf(channelBlocksNotifications(CandidateNotifier.CHANNEL_ID)) }
    var sleepChannelBlocked by remember { mutableStateOf(channelBlocksNotifications(SleepNightNotifier.CHANNEL_ID)) }

    fun refreshSharedDiagnostics() {
        permissionGranted = hasPermission()
        appEnabled = appNotificationsEnabled()
        requestablePermission = canRequestPermission()
        candidateChannelBlocked = channelBlocksNotifications(CandidateNotifier.CHANNEL_ID)
        sleepChannelBlocked = channelBlocksNotifications(SleepNightNotifier.CHANNEL_ID)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshSharedDiagnostics() }

    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // Refusing the permission never turns either setting back off: it is still what the
        // owner asked for, and Android may offer the prompt again later (e.g. after the
        // owner clears the "don't ask again" state from system settings). Only the
        // permission itself gates whether a notification actually posts.
        permissionGranted = granted
        requestablePermission = canRequestPermission()
    }

    fun launchRequestPermission() {
        container.notificationPreference.permissionRequested = true
        requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun openAppNotificationSettings(): Boolean = openIntentOrFalse(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )

    fun openChannelSettings(channelId: String): Boolean = openIntentOrFalse(
        context,
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId),
    )

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        NotificationChannelSection(
            explanationRes = R.string.notification_settings_explanation,
            toggleLabelRes = R.string.notification_settings_toggle_label,
            initialEnabled = container.notificationPreference.enabled,
            onEnabledChange = { container.notificationPreference.enabled = it },
            permissionGranted = permissionGranted,
            requestablePermission = requestablePermission,
            appEnabled = appEnabled,
            channelBlocked = candidateChannelBlocked,
            permissionMissingMessageRes = R.string.notification_settings_permission_missing,
            allClearMessageRes = R.string.notification_settings_all_clear,
            onRequestPermission = ::launchRequestPermission,
            onOpenAppSettings = ::openAppNotificationSettings,
            onOpenChannelSettings = { openChannelSettings(CandidateNotifier.CHANNEL_ID) },
            onSendTest = { container.candidateNotifier.sendTestNotification() },
            onRefresh = ::refreshSharedDiagnostics,
        )

        NotificationChannelSection(
            explanationRes = R.string.sleep_notification_settings_explanation,
            toggleLabelRes = R.string.sleep_notification_settings_toggle_label,
            initialEnabled = container.sleepNotificationPreference.enabled,
            onEnabledChange = { container.sleepNotificationPreference.enabled = it },
            permissionGranted = permissionGranted,
            requestablePermission = requestablePermission,
            appEnabled = appEnabled,
            channelBlocked = sleepChannelBlocked,
            permissionMissingMessageRes = R.string.sleep_notification_settings_permission_missing,
            allClearMessageRes = R.string.sleep_notification_settings_all_clear,
            onRequestPermission = ::launchRequestPermission,
            onOpenAppSettings = ::openAppNotificationSettings,
            onOpenChannelSettings = { openChannelSettings(SleepNightNotifier.CHANNEL_ID) },
            onSendTest = { container.sleepNightNotifier.sendTestNotification() },
            onRefresh = ::refreshSharedDiagnostics,
        )
    }
}

/**
 * One notification channel's whole Réglages presence: its own toggle, its own explanation,
 * and the diagnostics/fix-action/test-button machinery every channel in this app needs --
 * factored out here once a second channel (the morning sleep summary) needed the exact same
 * three platform checks [NotificationsSettingsSection]'s own kdoc describes. Only what is
 * genuinely per-channel is passed in: the toggle's own preference, the channel id, its own
 * explanation/permission-missing/all-clear wording (the only strings that mention what the
 * channel is actually for), and the send-test action. Everything else --
 * [permissionGranted]/[requestablePermission]/[appEnabled] -- is shared, computed once by
 * the caller, since Android grants `POST_NOTIFICATIONS` and the app-wide notification
 * switch once for the whole app, not per channel.
 */
@Composable
private fun NotificationChannelSection(
    explanationRes: Int,
    toggleLabelRes: Int,
    initialEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    permissionGranted: Boolean,
    requestablePermission: Boolean,
    appEnabled: Boolean,
    channelBlocked: Boolean,
    permissionMissingMessageRes: Int,
    allClearMessageRes: Int,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Boolean,
    onOpenChannelSettings: () -> Boolean,
    onSendTest: () -> Boolean,
    onRefresh: () -> Unit,
) {
    val colors = HelionThemeTokens.colors
    var enabled by remember { mutableStateOf(initialEnabled) }
    var intentUnavailable by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Boolean?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(explanationRes), style = HelionType.bodySmall, color = colors.textSecondary)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(
                checked = enabled,
                onCheckedChange = { checked ->
                    enabled = checked
                    onEnabledChange(checked)
                    if (checked && !permissionGranted) {
                        onRequestPermission()
                    }
                },
            )
            Text(stringResource(toggleLabelRes), style = HelionType.bodySmall, color = colors.textSecondary)
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
                Text(stringResource(allClearMessageRes), style = HelionType.bodySmall, color = colors.textSecondary)
            } else {
                SettingsWarning(stringResource(diagnosticsMessageRes(state, permissionMissingMessageRes, allClearMessageRes)))
                Button(
                    onClick = {
                        testResult = null
                        intentUnavailable = when (state) {
                            NotificationDiagnosticsState.PERMISSION_MISSING -> {
                                onRequestPermission()
                                false
                            }
                            NotificationDiagnosticsState.PERMISSION_DENIED, NotificationDiagnosticsState.APP_DISABLED -> !onOpenAppSettings()
                            NotificationDiagnosticsState.CHANNEL_DISABLED -> !onOpenChannelSettings()
                            NotificationDiagnosticsState.ALL_CLEAR -> false
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
                    testResult = onSendTest()
                    onRefresh()
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
}

/** Opens [intent] if something can handle it, returning whether it was actually opened. */
private fun openIntentOrFalse(context: android.content.Context, intent: Intent): Boolean = try {
    if (intent.resolveActivity(context.packageManager) == null) {
        false
    } else {
        context.startActivity(intent)
        true
    }
} catch (e: ActivityNotFoundException) {
    false
}

/**
 * The one Réglages label for each [NotificationDiagnosticsState] failure -- kept as a plain
 * mapping, not inlined into the composable, so every state is guaranteed a message the
 * moment it is added here. [permissionMissingMessageRes] and [allClearMessageRes] are the
 * only two states worth different wording per channel (they are the only ones that mention
 * what actually gets notified); every other message is generic Android-settings guidance,
 * identical for every channel.
 */
private fun diagnosticsMessageRes(state: NotificationDiagnosticsState, permissionMissingMessageRes: Int, allClearMessageRes: Int): Int = when (state) {
    NotificationDiagnosticsState.PERMISSION_MISSING -> permissionMissingMessageRes
    NotificationDiagnosticsState.PERMISSION_DENIED -> R.string.notification_settings_permission_denied
    NotificationDiagnosticsState.APP_DISABLED -> R.string.notification_settings_app_disabled
    NotificationDiagnosticsState.CHANNEL_DISABLED -> R.string.notification_settings_channel_disabled
    NotificationDiagnosticsState.ALL_CLEAR -> allClearMessageRes
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
 * - [CHANNEL_DISABLED]: the app is allowed to notify at all, but this particular channel
 *   is disabled or hidden.
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
 * to notify at all, and a given channel's own importance -- into exactly one
 * [NotificationDiagnosticsState], checked in the order that matches which fix actually
 * addresses which symptom: the permission first (it is what everything else depends on),
 * then the app-wide switch, then the channel. `null` when [preferenceEnabled] is off,
 * mirroring [ch.kevinjordil.helion.notification.CandidateNotifier]'s own gate: an owner who
 * chose not to be notified is not shown a diagnosis of a path he deliberately is not using.
 * Channel-agnostic on purpose: it takes the channel's own blocked state as a plain
 * `Boolean`, so the exact same function serves every channel this app has, one call per
 * channel.
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
