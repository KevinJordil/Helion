package ch.kevinjordil.helion.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ch.kevinjordil.helion.BuildConfig
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType

/**
 * Which build is actually installed -- several APKs share the same file name, so this is
 * the one place that tells them apart. Réglages' [SettingsSection.ABOUT] entry; the build
 * stamp itself is unchanged from before this screen was split out of one long scroll.
 */
@Composable
fun AboutSettingsSection() {
    val colors = HelionThemeTokens.colors
    Text(
        text = stringResource(R.string.settings_build_stamp, BuildConfig.BUILD_STAMP),
        style = HelionType.bodySmall,
        color = colors.textTertiary,
    )
}
