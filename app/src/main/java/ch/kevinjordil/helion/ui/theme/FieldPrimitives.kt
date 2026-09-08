package ch.kevinjordil.helion.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The label above any field anywhere in the app -- Réglages, the activity edit screen, slot
 * editing -- one style, used everywhere a field needs naming, so a date-of-birth label and a
 * start-time label are never set two different ways by accident. Deliberately not
 * uppercase/tracked like [HelionType.label]: a field label is read as a short sentence
 * ("Adresse du serveur"), not a fixed instrument tag.
 */
@Composable
fun HelionFieldLabel(text: String) {
    val colors = HelionThemeTokens.colors
    Text(text, style = HelionType.bodySmall, color = colors.textSecondary)
}

/**
 * The one presentation "this needs your attention" ever gets on a field: a validation error,
 * a refused permission, an unconfirmed plain-HTTP address, a malformed date. Amber stays
 * reserved for exactly this -- see [HelionColors.accentAmber]'s own kdoc -- so every such
 * message across every screen goes through this one composable rather than each screen
 * picking its own colour.
 */
@Composable
fun HelionWarning(text: String) {
    val colors = HelionThemeTokens.colors
    Text(text, style = HelionType.bodySmall, color = colors.accentAmber)
}

/**
 * One field, laid out the same way everywhere: its [HelionFieldLabel], a full-width
 * [OutlinedTextField], and an optional [HelionWarning] underneath when [warning] is
 * non-null. This is the one place a screen should reach for a labelled text field rather
 * than assembling the label/field/warning trio itself -- the root cause of the unevenness
 * this app kept hitting was every screen laying that trio out slightly differently.
 */
@Composable
fun HelionField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    warning: String? = null,
    placeholder: String? = null,
) {
    val colors = HelionThemeTokens.colors
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HelionFieldLabel(label)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
            isError = warning != null,
            placeholder = placeholder?.let { hint -> { Text(hint) } },
            // Amber, not Material's default red: amber is this app's one "needs your
            // attention" colour everywhere else (see HelionColors.accentAmber's own kdoc),
            // so a malformed field must not introduce a second, competing warning colour.
            colors = OutlinedTextFieldDefaults.colors(
                errorBorderColor = colors.accentAmber,
                errorCursorColor = colors.accentAmber,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        warning?.let { HelionWarning(it) }
    }
}
