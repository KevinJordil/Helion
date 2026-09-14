package ch.kevinjordil.helion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

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
            secondary = colors.accentViolet,
            secondaryContainer = colors.surfaceRaised,
            onSecondaryContainer = colors.textPrimary,
            surfaceContainer = colors.surface,
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
            secondary = colors.accentViolet,
            secondaryContainer = colors.surfaceRaised,
            onSecondaryContainer = colors.textPrimary,
            surfaceContainer = colors.surface,
        )
    }

    CompositionLocalProvider(LocalHelionColors provides colors) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = Typography(
                headlineSmall = HelionType.headline,
                titleLarge = HelionType.headline,
                titleMedium = HelionType.title,
                bodyLarge = HelionType.body,
                bodyMedium = HelionType.body,
                bodySmall = HelionType.bodySmall,
                labelLarge = HelionType.label,
                labelMedium = HelionType.labelSmall,
                labelSmall = HelionType.labelSmall,
            ),
            shapes = Shapes(
                small = RoundedCornerShape(10.dp),
                medium = RoundedCornerShape(HelionCornerRadiusSmall),
                large = RoundedCornerShape(HelionCornerRadius),
            ),
            content = content,
        )
    }
}

/** Convenience accessor: `HelionTheme.colors` inside any `@Composable`. */
object HelionThemeTokens {
    val colors: HelionColors
        @Composable get() = LocalHelionColors.current
}
