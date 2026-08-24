package ch.kevinjordil.helion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.ui.metrics.MetricsScreen
import ch.kevinjordil.helion.ui.settings.SettingsScreen
import ch.kevinjordil.helion.ui.today.TodayScreen

/** The three destinations of the app. Metric-specific screens are not part of this shell yet. */
enum class Destination(val labelRes: Int) {
    TODAY(R.string.tab_today),
    METRICS(R.string.tab_metrics),
    SETTINGS(R.string.tab_settings),
}

/**
 * Top-level navigation shell. Plain enum-backed state rather than a navigation library:
 * three flat, non-nested destinations do not need a back stack or deep-link graph, and
 * [AppContainer]'s own kdoc sets the precedent -- "a framework would cost more than it
 * saves" -- for exactly this kind of small, closed set of screens.
 */
@Composable
fun HelionNavHost(container: AppContainer, modifier: Modifier = Modifier) {
    var destination by rememberSaveable { mutableStateOf(Destination.TODAY) }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = destination == entry,
                        onClick = { destination = entry },
                        icon = {},
                        label = { Text(stringResource(entry.labelRes)) },
                    )
                }
            }
        },
    ) { contentPadding ->
        Box(Modifier.padding(contentPadding)) {
            when (destination) {
                Destination.TODAY -> TodayScreen(container)
                Destination.METRICS -> MetricsScreen()
                Destination.SETTINGS -> SettingsScreen(container)
            }
        }
    }
}
