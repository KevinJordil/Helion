package ch.kevinjordil.helion.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.customserver.CustomServerUrlValidation
import ch.kevinjordil.helion.customserver.validateCustomServerUrl
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType

/**
 * Where the owner points Helion at his own always-on server: its URL, the shared token
 * sent as `Authorization: Bearer <token>` on every send, the one deliberate confirmation a
 * plain-`http://` URL requires (see [CustomServerConfig.allowPlainHttp]'s own kdoc), and the
 * device name written into the TCX file's `<Creator>` element -- what Strava itself shows as
 * the recording device (see [ch.kevinjordil.helion.export.writeTcx]'s own kdoc). All four
 * live together under Réglages' [SettingsSection.STRAVA] entry since all four are about the
 * one thing the owner comes here to do: control what shows up on Strava and how it gets
 * there.
 *
 * Every field saves itself the moment it holds a value, same self-saving pattern as every
 * other field in Réglages -- except the URL, which is only written back once
 * [validateCustomServerUrl] actually accepts it, so a half-typed address never overwrites a
 * working one.
 */
@Composable
fun CustomServerSettingsSection(container: AppContainer) {
    val colors = HelionThemeTokens.colors
    val config = container.customServerConfig

    var urlText by remember { mutableStateOf(config.serverUrl.orEmpty()) }
    var tokenText by remember { mutableStateOf(config.token.orEmpty()) }
    var allowPlainHttp by remember { mutableStateOf(config.allowPlainHttp) }
    var deviceNameText by remember { mutableStateOf(container.recordingDeviceName.value) }

    val validation = validateCustomServerUrl(urlText)

    SettingsFieldLabel(stringResource(R.string.custom_server_url_label))
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
        SettingsWarning(stringResource(R.string.custom_server_url_invalid))
    }

    SettingsFieldLabel(stringResource(R.string.custom_server_token_label))
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

    SettingsFieldLabel(stringResource(R.string.recording_device_name_label))
    OutlinedTextField(
        value = deviceNameText,
        onValueChange = { text ->
            deviceNameText = text
            // Stored as typed, blank included -- a blank value is what tells writeTcx to
            // omit <Creator> entirely rather than write one with an empty name (see
            // RecordingDeviceName's own kdoc).
            container.recordingDeviceName.value = text
        },
        singleLine = true,
    )
    Text(stringResource(R.string.recording_device_name_note), style = HelionType.bodySmall, color = colors.textSecondary)
}
