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
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.ui.activity.ActivityDetailScreen
import ch.kevinjordil.helion.ui.activity.ActivityListScreen
import ch.kevinjordil.helion.ui.activity.DayTimelineScreen
import ch.kevinjordil.helion.ui.activity.SlotEditScreen
import ch.kevinjordil.helion.ui.activity.SlotListScreen
import ch.kevinjordil.helion.ui.home.HomeScreen
import ch.kevinjordil.helion.ui.metric.MetricCatalog
import ch.kevinjordil.helion.ui.metric.MetricScreen
import ch.kevinjordil.helion.ui.settings.SettingsScreen
import ch.kevinjordil.helion.ui.settings.SettingsSectionScreen
import ch.kevinjordil.helion.ui.sleep.SleepScreen

private const val METRIC_ROUTE_PREFIX = "metric:"
private const val ACTIVITY_ROUTE_PREFIX = "activity:"
private const val DAY_TIMELINE_ROUTE = "day_timeline"
private const val SLOTS_ROUTE = "slots"
private const val SLOT_ROUTE_PREFIX = "slot:"
private const val SETTINGS_SECTION_ROUTE_PREFIX = "settings:"

/** The [SlotEditScreen] route id for a brand-new slot, as opposed to `"slot:<id>"` for an existing one. */
private const val NEW_SLOT_ID = "new"

/** The activity-detail route for [id] -- the one route a candidate notification's tap opens directly. */
fun activityDetailRoute(id: Long): String = "$ACTIVITY_ROUTE_PREFIX$id"

/** The Activités list route -- what a batch notification's tap opens. */
// A computed accessor, deliberately NOT an initialised top-level val: as a val it runs
// during this file class's static initialisation, which forces RootDestination's own
// initialisation, which reads SleepIcon and ActivityIcon below -- still null at that
// point, and permanently so, since the JVM will not re-enter an initialisation already
// in progress. That produced null tab icons and a crash on the first frame.
val activitiesListRoute: String get() = RootDestination.ACTIVITIES.route

/**
 * The one route a notification tap wants [HelionNavHost] to land on next, set by
 * [ch.kevinjordil.helion.MainActivity] from either a cold start's launch intent or a warm
 * one's [android.app.Activity.onNewIntent]. A plain observable singleton rather than a
 * navigation library's deep-link graph, for the same reason [HelionNavHost]'s own kdoc
 * gives for hand-rolling its back stack: the whole graph here is small enough that a
 * library would cost more than it saves. [HelionNavHost] consumes and clears it the moment
 * it is composed with a non-null value, so a value set once is applied exactly once.
 */
object NotificationNavigationTarget {
    var route: String? by mutableStateOf(null)
}

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
 * A stopwatch for the Activités tab. Same reasoning as [SleepIcon]: no new dependency for
 * one bespoke glyph. A circle (the dial) with a small button on top and a lap hand pointing
 * to about "two o'clock" -- legible at 24x24 without relying on colour.
 */
private val ActivityIcon: ImageVector = ImageVector.Builder(
    name = "Activity",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        // Crown button on top of the dial.
        moveTo(10f, 1f)
        lineTo(14f, 1f)
        lineTo(14f, 3f)
        lineTo(10f, 3f)
        close()
    }
    path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
        // The dial itself, as a ring: an outer circle with an inner circle cut out of it
        // by the even-odd fill rule below, rather than two separately filled discs.
        moveTo(12f, 4f)
        curveToRelative(-4.97f, 0f, -9f, 4.03f, -9f, 9f)
        curveToRelative(0f, 4.97f, 4.03f, 9f, 9f, 9f)
        curveToRelative(4.97f, 0f, 9f, -4.03f, 9f, -9f)
        curveToRelative(0f, -4.97f, -4.03f, -9f, -9f, -9f)
        close()
        moveTo(12f, 6f)
        curveToRelative(3.87f, 0f, 7f, 3.13f, 7f, 7f)
        curveToRelative(0f, 3.87f, -3.13f, 7f, -7f, 7f)
        curveToRelative(-3.87f, 0f, -7f, -3.13f, -7f, -7f)
        curveToRelative(0f, -3.87f, 3.13f, -7f, 7f, -7f)
        close()
    }
    path(fill = SolidColor(Color.Black)) {
        // Lap hand from the centre toward two o'clock.
        moveTo(11.25f, 9f)
        lineTo(12.75f, 9f)
        lineTo(12.75f, 13.25f)
        lineTo(16f, 15.15f)
        lineTo(15.25f, 16.45f)
        lineTo(11.25f, 14.1f)
        close()
    }
}.build()

/**
 * The navigation bar's root destinations: Accueil, Sommeil, Activités and Réglages. Kept as
 * a plain list a `when` can exhaust, so adding another destination never needs to touch the
 * bar's rendering or the back-stack logic below.
 */
enum class RootDestination(val route: String, val labelRes: Int, val icon: ImageVector) {
    HOME("home", R.string.tab_home, Icons.Filled.Home),
    SLEEP("sleep", R.string.tab_sleep, SleepIcon),
    ACTIVITIES("activities", R.string.tab_activities, ActivityIcon),
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

    // A notification tap (cold start or warm, see NotificationNavigationTarget's own kdoc)
    // replaces the whole back stack with its target rather than pushing onto whatever was
    // there before -- the same "land here directly, don't make him hunt for it" reasoning
    // the notification feature exists for in the first place.
    LaunchedEffect(NotificationNavigationTarget.route) {
        NotificationNavigationTarget.route?.let { route ->
            backStack = listOf(route)
            NotificationNavigationTarget.route = null
        }
    }

    fun openMetric(metricId: String) {
        backStack = backStack + "$METRIC_ROUTE_PREFIX$metricId"
    }

    fun selectRoot(route: String) {
        backStack = listOf(route)
    }

    fun popBack() {
        backStack = backStack.dropLast(1)
    }

    fun openActivity(id: Long) {
        backStack = backStack + "$ACTIVITY_ROUTE_PREFIX$id"
    }

    fun openDayTimeline() {
        backStack = backStack + DAY_TIMELINE_ROUTE
    }

    fun openSlots() {
        backStack = backStack + SLOTS_ROUTE
    }

    fun openSlot(id: String) {
        backStack = backStack + "$SLOT_ROUTE_PREFIX$id"
    }

    fun openSettingsSection(sectionId: String) {
        backStack = backStack + "$SETTINGS_SECTION_ROUTE_PREFIX$sectionId"
    }

    /**
     * Called when [DayTimelineScreen] turns a selection into a new activity: the timeline
     * entry is replaced by the new activity's detail rather than pushed under it, so the
     * system back gesture from that detail screen returns straight to the Activités list,
     * not back through the timeline that only exists to create it.
     */
    fun replaceWithActivity(id: Long) {
        backStack = backStack.dropLast(1) + "$ACTIVITY_ROUTE_PREFIX$id"
    }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                RootDestination.entries.forEach { entry ->
                    val selected = current == entry.route ||
                        (entry == RootDestination.HOME && current.startsWith(METRIC_ROUTE_PREFIX)) ||
                        (
                            entry == RootDestination.ACTIVITIES &&
                                (
                                    current.startsWith(ACTIVITY_ROUTE_PREFIX) ||
                                        current == DAY_TIMELINE_ROUTE ||
                                        current == SLOTS_ROUTE ||
                                        current.startsWith(SLOT_ROUTE_PREFIX)
                                )
                            ) ||
                        (entry == RootDestination.SETTINGS && current.startsWith(SETTINGS_SECTION_ROUTE_PREFIX))
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
                current == RootDestination.SETTINGS.route -> SettingsScreen(container, onOpenSection = ::openSettingsSection)

                current.startsWith(SETTINGS_SECTION_ROUTE_PREFIX) -> SettingsSectionScreen(
                    container = container,
                    sectionId = current.removePrefix(SETTINGS_SECTION_ROUTE_PREFIX),
                    onBack = ::popBack,
                )

                current == RootDestination.SLEEP.route -> SleepScreen(container)

                current == RootDestination.ACTIVITIES.route -> ActivityListScreen(
                    container = container,
                    onOpenActivity = ::openActivity,
                    onNewActivity = ::openDayTimeline,
                    onManageSlots = ::openSlots,
                )

                current.startsWith(ACTIVITY_ROUTE_PREFIX) -> {
                    val activityId = current.removePrefix(ACTIVITY_ROUTE_PREFIX).toLongOrNull()
                    if (activityId != null) {
                        ActivityDetailScreen(
                            container = container,
                            activityId = activityId,
                            onBack = ::popBack,
                            onDeleted = ::popBack,
                        )
                    } else {
                        LaunchedEffect(current) { backStack = listOf(RootDestination.ACTIVITIES.route) }
                    }
                }

                current == DAY_TIMELINE_ROUTE -> DayTimelineScreen(
                    container = container,
                    onBack = ::popBack,
                    onActivityCreated = ::replaceWithActivity,
                )

                current == SLOTS_ROUTE -> SlotListScreen(
                    container = container,
                    onBack = ::popBack,
                    onOpenSlot = { openSlot(it.toString()) },
                    onNewSlot = { openSlot(NEW_SLOT_ID) },
                )

                current.startsWith(SLOT_ROUTE_PREFIX) -> {
                    val slotIdText = current.removePrefix(SLOT_ROUTE_PREFIX)
                    SlotEditScreen(
                        container = container,
                        slotId = if (slotIdText == NEW_SLOT_ID) null else slotIdText.toLongOrNull(),
                        onBack = ::popBack,
                        onSaved = ::popBack,
                        onDeleted = ::popBack,
                    )
                }

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
