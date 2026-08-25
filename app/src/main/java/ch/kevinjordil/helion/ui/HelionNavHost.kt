package ch.kevinjordil.helion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.ui.home.HomeScreen
import ch.kevinjordil.helion.ui.metric.MetricCatalog
import ch.kevinjordil.helion.ui.metric.MetricScreen
import ch.kevinjordil.helion.ui.settings.SettingsScreen
import ch.kevinjordil.helion.ui.sleep.SleepScreen

private const val METRIC_ROUTE_PREFIX = "metric:"

/**
 * A crescent moon for the Sommeil tab. Hand-drawn rather than pulled from
 * material-icons-extended: that module is not a dependency (see the app's "no new Gradle
 * dependencies" rule), and this app's icon needs are small enough that one bespoke vector
 * is cheaper than a new artifact. Standard 24x24 Material-style viewport, so it sits at the
 * same visual weight as [Icons.Filled.Home] and [Icons.Filled.Settings] beside it.
 */
private val SleepIcon: ImageVector = ImageVector.Builder(
    name = "Sleep",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 3f)
        curveToRelative(-4.97f, 0f, -9f, 4.03f, -9f, 9f)
        curveToRelative(0f, 4.97f, 4.03f, 9f, 9f, 9f)
        curveToRelative(4.13f, 0f, 7.6f, -2.79f, 8.66f, -6.58f)
        curveToRelative(-0.21f, 0.05f, -0.42f, 0.07f, -0.66f, 0.07f)
        curveToRelative(-3.31f, 0f, -6f, -2.69f, -6f, -6f)
        curveToRelative(0f, -2.12f, 1.11f, -3.98f, 2.77f, -5.04f)
        curveTo(15.24f, 3.23f, 13.66f, 3f, 12f, 3f)
        close()
    }
}.build()

/**
 * The navigation bar's root destinations: Accueil, Sommeil and Réglages. Kept as a plain
 * list a `when` can exhaust, deliberately not hardcoding "three" anywhere else, so a fourth
 * destination (Activités) can be appended here later without touching the bar's rendering
 * or the back-stack logic below.
 */
enum class RootDestination(val route: String, val labelRes: Int, val icon: ImageVector) {
    HOME("home", R.string.tab_home, Icons.Filled.Home),
    SLEEP("sleep", R.string.tab_sleep, SleepIcon),
    SETTINGS("settings", R.string.tab_settings, Icons.Filled.Settings),
}

/**
 * Top-level navigation shell. A hand-rolled back stack -- a plain `List<String>` of route
 * strings -- rather than a navigation library: the whole graph is two root destinations
 * plus one parameterised detail pushed on top of Accueil, which does not need a library's
 * deep-link graph or multi-back-stack machinery, and [AppContainer]'s own kdoc sets the
 * precedent -- "a framework would cost more than it saves" -- for exactly this kind of
 * small, closed navigation graph.
 *
 * The metric detail is a real entry in [backStack], parameterised by metric id
 * (`"metric:<id>"`), not screen-local state: this is what lets the single [BackHandler]
 * below pop it like any other destination. That one handler is the navigation shell's own
 * back-stack mechanism, installed once, here -- not the kind of workaround
 * `MetricsScreen`'s old drill-down needed, which had no back stack for the system gesture
 * to act on at all.
 */
@Composable
fun HelionNavHost(container: AppContainer, modifier: Modifier = Modifier) {
    var backStack by rememberSaveable { mutableStateOf(listOf(RootDestination.HOME.route)) }
    val current = backStack.last()

    BackHandler(enabled = backStack.size > 1) {
        backStack = backStack.dropLast(1)
    }

    fun openMetric(metricId: String) {
        backStack = backStack + "$METRIC_ROUTE_PREFIX$metricId"
    }

    fun selectRoot(route: String) {
        backStack = listOf(route)
    }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                RootDestination.entries.forEach { entry ->
                    val selected = current == entry.route ||
                        (entry == RootDestination.HOME && current.startsWith(METRIC_ROUTE_PREFIX))
                    NavigationBarItem(
                        selected = selected,
                        onClick = { selectRoot(entry.route) },
                        icon = { Icon(entry.icon, contentDescription = null) },
                        label = { Text(stringResource(entry.labelRes)) },
                    )
                }
            }
        },
    ) { contentPadding ->
        Box(Modifier.padding(contentPadding)) {
            when {
                current == RootDestination.SETTINGS.route -> SettingsScreen(container)

                current == RootDestination.SLEEP.route -> SleepScreen(container)

                current.startsWith(METRIC_ROUTE_PREFIX) -> {
                    val metricId = current.removePrefix(METRIC_ROUTE_PREFIX)
                    val metric = MetricCatalog.byId(metricId)
                    if (metric != null) {
                        MetricScreen(
                            container = container,
                            metric = metric,
                            onBack = { backStack = backStack.dropLast(1) },
                        )
                    } else {
                        // A saved id that no longer matches a catalog entry: fall back to
                        // Accueil instead of a permanently broken detail screen. See
                        // MetricCatalog.byId's kdoc -- the same "outlives the catalog"
                        // concern applies here, since the route survives process death too.
                        LaunchedEffect(current) { backStack = listOf(RootDestination.HOME.route) }
                    }
                }

                else -> HomeScreen(
                    container = container,
                    onOpenMetric = ::openMetric,
                    onOpenSettings = { selectRoot(RootDestination.SETTINGS.route) },
                )
            }
        }
    }
}
