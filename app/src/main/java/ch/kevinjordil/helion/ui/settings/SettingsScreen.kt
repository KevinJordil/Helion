package ch.kevinjordil.helion.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.BuildConfig
import ch.kevinjordil.helion.customserver.CustomServerUrlValidation
import ch.kevinjordil.helion.customserver.validateCustomServerUrl
import ch.kevinjordil.helion.strava.StravaConfig
import ch.kevinjordil.helion.strava.missingUploadScope
import ch.kevinjordil.helion.ui.activity.stravaAuthFailureArgs
import ch.kevinjordil.helion.ui.activity.stravaAuthFailureRes
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** `dd/MM/yyyy`, the same day-month-year order every other date field in the app uses (see [ch.kevinjordil.helion.ui.activity.ACTIVITY_DATETIME_FORMAT]), without a time-of-day component: a date of birth has none. */
private val DATE_OF_BIRTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

private fun parseDateOfBirth(text: String): LocalDate? = try {
    LocalDate.parse(text.trim(), DATE_OF_BIRTH_FORMAT)
} catch (e: DateTimeParseException) {
    null
}

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
    var stepsGoalText by remember { mutableStateOf(container.stepsGoal.value.toString()) }

    var dateOfBirthText by remember {
        mutableStateOf(container.profile.dateOfBirthEpochDay?.let { DATE_OF_BIRTH_FORMAT.format(LocalDate.ofEpochDay(it)) }.orEmpty())
    }
    var weightText by remember { mutableStateOf(container.profile.weightKg?.toString().orEmpty()) }
    var selectedSex by remember { mutableStateOf(container.profile.sex) }

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
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tab_settings).uppercase(), style = HelionType.label, color = colors.textSecondary)

        StravaSection(container)

        HorizontalDivider(color = colors.divider)

        CustomServerSection(container)

        // Which build is actually installed: several APKs share the same file name.
        Text(
            text = stringResource(R.string.settings_build_stamp, BuildConfig.BUILD_STAMP),
            style = HelionType.bodySmall,
            color = colors.textTertiary,
        )

        HorizontalDivider(color = colors.divider)

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

        HorizontalDivider(color = colors.divider)

        Text(stringResource(R.string.steps_goal_label), style = HelionType.body, color = colors.textPrimary)
        OutlinedTextField(
            value = stepsGoalText,
            onValueChange = { text ->
                stepsGoalText = text
                // Only a valid, positive whole number is actually stored -- an in-progress
                // or empty edit must not silently reset the goal a metric comparison
                // elsewhere is reading from.
                text.toIntOrNull()?.takeIf { it > 0 }?.let { container.stepsGoal.value = it }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )

        HorizontalDivider(color = colors.divider)

        NotificationsSection(container)

        HorizontalDivider(color = colors.divider)

        Text(stringResource(R.string.profile_section_title).uppercase(), style = HelionType.label, color = colors.textSecondary)

        Text(stringResource(R.string.profile_date_of_birth_label), style = HelionType.bodySmall, color = colors.textSecondary)
        OutlinedTextField(
            value = dateOfBirthText,
            onValueChange = { text ->
                dateOfBirthText = text
                // Same "save the moment it's valid, never a half-typed guess" rule as every
                // other self-saving field on this screen and on the activity detail screen:
                // an empty or unparsable date must never silently store a birth date, and a
                // blank field explicitly clears one that was set before.
                if (text.isBlank()) {
                    container.profile.dateOfBirthEpochDay = null
                } else {
                    parseDateOfBirth(text)?.let { container.profile.dateOfBirthEpochDay = it.toEpochDay() }
                }
            },
            singleLine = true,
        )
        if (dateOfBirthText.isNotBlank() && parseDateOfBirth(dateOfBirthText) == null) {
            Text(stringResource(R.string.profile_date_of_birth_invalid), style = HelionType.bodySmall, color = colors.accentAmber)
        }

        Text(stringResource(R.string.profile_weight_label), style = HelionType.bodySmall, color = colors.textSecondary)
        OutlinedTextField(
            value = weightText,
            onValueChange = { text ->
                weightText = text
                if (text.isBlank()) {
                    container.profile.weightKg = null
                } else {
                    text.toFloatOrNull()?.takeIf { it > 0f }?.let { container.profile.weightKg = it }
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
        )

        Text(stringResource(R.string.profile_sex_label), style = HelionType.bodySmall, color = colors.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(Sex.MALE to R.string.profile_sex_male, Sex.FEMALE to R.string.profile_sex_female).forEach { (sex, labelRes) ->
                Text(
                    stringResource(labelRes).uppercase(),
                    style = HelionType.label,
                    color = if (sex == selectedSex) colors.accentViolet else colors.textTertiary,
                    modifier = Modifier.clickable {
                        selectedSex = sex
                        container.profile.sex = sex
                    },
                )
            }
        }

        Text(stringResource(R.string.profile_note), style = HelionType.bodySmall, color = colors.textSecondary)
    }
}

/**
 * Where the owner actually sets Strava up: connect, disconnect and see what went wrong,
 * rather than only inside an activity he has not created yet. Reads
 * [ch.kevinjordil.helion.strava.StravaAuth.status] live -- the same [collectAsState] the
 * activity detail screen uses -- so completing (or declining) the browser flow updates this
 * section immediately, with no need to leave Réglages and come back.
 */
@Composable
private fun StravaSection(container: AppContainer) {
    val colors = HelionThemeTokens.colors
    val context = LocalContext.current
    val authStatus by container.stravaAuth.status.collectAsState()

    Text(stringResource(R.string.strava_settings_section_title).uppercase(), style = HelionType.label, color = colors.textSecondary)

    if (!StravaConfig.isConfigured) {
        Text(stringResource(R.string.strava_reason_not_configured), style = HelionType.bodySmall, color = colors.textSecondary)
        return
    }

    Text(
        stringResource(if (authStatus.connected) R.string.strava_settings_connected else R.string.strava_settings_not_connected),
        style = HelionType.bodySmall,
        color = colors.textSecondary,
    )

    // The scope Strava's own token response actually granted (see StravaAuth.exchangeCode),
    // shown plainly so the owner can see at a glance whether he has upload permission --
    // rather than only discovering a missing scope once a publish attempt fails with 401.
    if (authStatus.connected) {
        val scope = authStatus.grantedScope
        Text(
            if (scope != null) {
                stringResource(R.string.strava_settings_scope, scope)
            } else {
                stringResource(R.string.strava_settings_scope_unknown)
            },
            style = HelionType.bodySmall,
            color = colors.textSecondary,
        )
        if (authStatus.missingUploadScope()) {
            Text(
                stringResource(R.string.strava_scope_write_missing),
                style = HelionType.bodySmall,
                color = colors.accentAmber,
            )
        }
    }

    authStatus.lastFailure?.let { failure ->
        Text(
            stringResource(stravaAuthFailureRes(failure), *stravaAuthFailureArgs(failure).toTypedArray()),
            style = HelionType.bodySmall,
            color = colors.accentAmber,
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (authStatus.connected) {
            OutlinedButton(onClick = { container.stravaAuth.disconnect() }) {
                Text(stringResource(R.string.strava_disconnect_action))
            }
        }
        if (!authStatus.connected || authStatus.missingUploadScope()) {
            Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(container.stravaAuth.authorizeUrl()))) }) {
                Text(stringResource(if (authStatus.connected) R.string.strava_reconnect_action else R.string.strava_connect_action))
            }
        }
    }
}

/**
 * Where the owner points Helion at his own always-on server: its URL, the shared token
 * sent as `Authorization: Bearer <token>` on every send, and the one deliberate confirmation
 * a plain-`http://` URL requires (see [CustomServerConfig.allowPlainHttp]'s own kdoc) --
 * health data belongs behind TLS, and this is the one place that gets overridden on
 * purpose, not silently.
 *
 * Every field saves itself the moment it holds a value, same self-saving pattern as every
 * other field on this screen -- except the URL, which is only written back once
 * [validateCustomServerUrl] actually accepts it, so a half-typed address never overwrites a
 * working one.
 */
@Composable
private fun CustomServerSection(container: AppContainer) {
    val colors = HelionThemeTokens.colors
    val config = container.customServerConfig

    var urlText by remember { mutableStateOf(config.serverUrl.orEmpty()) }
    var tokenText by remember { mutableStateOf(config.token.orEmpty()) }
    var allowPlainHttp by remember { mutableStateOf(config.allowPlainHttp) }

    val validation = validateCustomServerUrl(urlText)

    Text(stringResource(R.string.custom_server_settings_section_title).uppercase(), style = HelionType.label, color = colors.textSecondary)

    Text(stringResource(R.string.custom_server_url_label), style = HelionType.bodySmall, color = colors.textSecondary)
    OutlinedTextField(
        value = urlText,
        onValueChange = { text ->
            urlText = text
            // Only a URL validateCustomServerUrl actually accepts is stored -- an
            // in-progress or malformed edit must never silently overwrite a working
            // address (same "save only once valid" rule the profile fields use). A blank
            // field explicitly clears a previously configured one.
            when (val result = validateCustomServerUrl(text)) {
                CustomServerUrlValidation.Blank -> config.serverUrl = null
                is CustomServerUrlValidation.Valid -> config.serverUrl = text.trim()
                CustomServerUrlValidation.Malformed -> Unit
            }
        },
        singleLine = true,
    )
    if (validation == CustomServerUrlValidation.Malformed) {
        Text(stringResource(R.string.custom_server_url_invalid), style = HelionType.bodySmall, color = colors.accentAmber)
    }

    Text(stringResource(R.string.custom_server_token_label), style = HelionType.bodySmall, color = colors.textSecondary)
    OutlinedTextField(
        value = tokenText,
        onValueChange = { text ->
            tokenText = text
            config.token = text.ifBlank { null }
        },
        singleLine = true,
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(
            checked = allowPlainHttp,
            onCheckedChange = { checked ->
                allowPlainHttp = checked
                config.allowPlainHttp = checked
            },
        )
        Text(stringResource(R.string.custom_server_allow_plain_http), style = HelionType.bodySmall, color = colors.textSecondary)
    }
}

/**
 * The on/off switch for candidate-detection notifications, plus the moment Android 13's
 * `POST_NOTIFICATIONS` permission is actually asked for: only when the owner ticks this
 * checkbox on, not at first launch, so the French rationale text above it is read in the
 * context it explains rather than a generic first-run dialog. Declining leaves
 * [NotificationPreference.enabled] on -- candidates simply keep appearing in Activités,
 * silently, exactly as [ch.kevinjordil.helion.notification.CandidateNotifier]'s own kdoc
 * describes -- with [permissionDenied] surfacing that explanation right here instead of
 * leaving the owner wondering why nothing showed up.
 */
@Composable
private fun NotificationsSection(container: AppContainer) {
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

    Text(stringResource(R.string.notification_settings_section_title).uppercase(), style = HelionType.label, color = colors.textSecondary)
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
        Text(stringResource(R.string.notification_settings_permission_denied), style = HelionType.bodySmall, color = colors.accentAmber)
    }
}
