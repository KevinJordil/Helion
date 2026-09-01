package ch.kevinjordil.helion.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType

/**
 * The label above a field on any Réglages sub-screen -- one style, used everywhere a field
 * needs naming, so a date-of-birth label and a server-URL label are never set two different
 * ways by accident. Deliberately not uppercase/tracked like [HelionType.label]: a field
 * label is read as a short sentence ("Adresse du serveur"), not a fixed instrument tag.
 */
@Composable
fun SettingsFieldLabel(text: String) {
    val colors = HelionThemeTokens.colors
    Text(text, style = HelionType.bodySmall, color = colors.textSecondary)
}

/**
 * The one presentation "this needs your attention" ever gets on a Réglages sub-screen: a
 * validation error, a refused permission, an unconfirmed plain-HTTP address. Amber stays
 * reserved for exactly this -- see [ch.kevinjordil.helion.ui.theme.HelionColors.accentAmber]'s
 * own kdoc -- so every such message across every sub-screen goes through this one composable
 * rather than each section picking its own colour.
 */
@Composable
fun SettingsWarning(text: String) {
    val colors = HelionThemeTokens.colors
    Text(text, style = HelionType.bodySmall, color = colors.accentAmber)
}
