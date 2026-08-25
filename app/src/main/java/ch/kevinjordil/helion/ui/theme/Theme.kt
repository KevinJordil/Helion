package ch.kevinjordil.helion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/** [HelionColors] for whichever theme is active. Read with [HelionTheme.colors] inside content. */
val LocalHelionColors = staticCompositionLocalOf { HelionDarkColors }

/**
 * Helion's own theme: a small, closed palette (see [HelionColors]) and a bespoke type
 * scale ([HelionType]), not Material's defaults. A [MaterialTheme] is still installed
 * underneath, mapped onto the same tokens, because a few Material3 components are used
 * as-is (the navigation bar, the pull-to-refresh indicator) and must not fall back to
 * Material's stock purple.
 */
@Composable
fun HelionTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) HelionDarkColors else HelionLightColors

    val materialScheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = colors.accentViolet,
            onPrimary = colors.onAccentViolet,
            error = colors.accentAmber,
            onError = colors.onAccentAmber,
            background = colors.ground,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceRaised,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.divider,
        )
    } else {
        lightColorScheme(
            primary = colors.accentViolet,
            onPrimary = colors.onAccentViolet,
            error = colors.accentAmber,
            onError = colors.onAccentAmber,
            background = colors.ground,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceRaised,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.divider,
        )
    }

    CompositionLocalProvider(LocalHelionColors provides colors) {
        MaterialTheme(colorScheme = materialScheme, content = content)
    }
}

/** Convenience accessor: `HelionTheme.colors` inside any `@Composable`. */
object HelionThemeTokens {
    val colors: HelionColors
        @Composable get() = LocalHelionColors.current
}
