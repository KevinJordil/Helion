package ch.kevinjordil.helion.ui.settings

import androidx.compose.runtime.Composable
import ch.kevinjordil.helion.ui.theme.HelionFieldLabel
import ch.kevinjordil.helion.ui.theme.HelionWarning

/**
 * Réglages' own names for the app-wide field primitives (see [HelionFieldLabel] and
 * [HelionWarning] in `ui.theme.FieldPrimitives.kt`) -- kept as thin aliases so every existing
 * call site in this package needs no change, while the actual style lives in one shared
 * place the activity edit screen and slot editing now reach for too.
 */
@Composable
fun SettingsFieldLabel(text: String) = HelionFieldLabel(text)

@Composable
fun SettingsWarning(text: String) = HelionWarning(text)
