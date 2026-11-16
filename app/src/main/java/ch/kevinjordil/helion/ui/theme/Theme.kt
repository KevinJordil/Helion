package ch.kevinjordil.helion.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/** [HelionColors] for the app. Read with [HelionThemeTokens.colors] inside content. */
val LocalHelionColors = staticCompositionLocalOf { HelionDarkColors }

/**
 * Helion's theme: a single OLED-black palette ([HelionColors]) and its own type scale
 * ([HelionType]). The app is dark whatever the system setting, so there is no light
 * variant to drift out of step.
 *
 * A [MaterialTheme] is installed underneath, mapped onto the same tokens, because a few
 * Material3 components are used as they are (switches, text fields, dialogs, the
 * pull-to-refresh indicator) and must not fall back to Material's stock purple.
 */
@Composable
fun HelionTheme(content: @Composable () -> Unit) {
    val colors = HelionDarkColors
    val materialScheme = darkColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        primaryContainer = colors.accent.copy(alpha = 0.16f),
        onPrimaryContainer = colors.textPrimary,
        secondary = colors.accent,
        onSecondary = colors.onAccent,
        secondaryContainer = colors.surfaceRaised,
        onSecondaryContainer = colors.textPrimary,
        error = colors.attention,
        onError = colors.onAttention,
        background = colors.ground,
        onBackground = colors.textPrimary,
        surface = colors.surface,
        onSurface = colors.textPrimary,
        surfaceVariant = colors.surfaceRaised,
        onSurfaceVariant = colors.textSecondary,
        surfaceContainerLowest = colors.ground,
        surfaceContainerLow = colors.surfaceDim,
        surfaceContainer = colors.surface,
        surfaceContainerHigh = colors.surfaceRaised,
        surfaceContainerHighest = colors.surfaceRaised,
        outline = colors.divider,
        outlineVariant = colors.hairline,
        scrim = colors.ground,
    )

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
                extraSmall = RoundedCornerShape(HelionCornerRadiusSmall),
                small = RoundedCornerShape(HelionCornerRadiusSmall),
                medium = RoundedCornerShape(HelionControlRadius),
                large = RoundedCornerShape(HelionCornerRadius),
                extraLarge = RoundedCornerShape(HelionCornerRadius),
            ),
            content = content,
        )
    }
}

/** Convenience accessor: `HelionThemeTokens.colors` inside any `@Composable`. */
object HelionThemeTokens {
    val colors: HelionColors
        @Composable get() = LocalHelionColors.current
}
