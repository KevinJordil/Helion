package ch.kevinjordil.helion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import ch.kevinjordil.helion.ui.theme.HelionIcons
import ch.kevinjordil.helion.ui.theme.HelionMotion
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import ch.kevinjordil.helion.ui.theme.HorizontalRule

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
// initialisation, which reads the tab icons -- possibly still null at that
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
 * The navigation bar's root destinations: Accueil, Sommeil, Activités and Réglages. Kept as
 * a plain list a `when` can exhaust, so adding another destination never needs to touch the
 * bar's rendering or the back-stack logic below.
 */
enum class RootDestination(val route: String, val labelRes: Int, val icon: ImageVector) {
    HOME("home", R.string.tab_home, HelionIcons.Today),
    SLEEP("sleep", R.string.tab_sleep, HelionIcons.Moon),
    ACTIVITIES("activities", R.string.tab_activities, HelionIcons.Stopwatch),
    SETTINGS("settings", R.string.tab_settings, HelionIcons.Sliders),
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
    // Which way the last navigation went, so the transition can tell a push from a pop
    // from a tab switch: +1 deeper, -1 back, 0 sideways.
    var direction by rememberSaveable { mutableIntStateOf(0) }
    val current = backStack.last()

    fun push(route: String) {
        direction = 1
        backStack = backStack + route
    }

    fun popBack() {
        direction = -1
        backStack = backStack.dropLast(1)
    }

    BackHandler(enabled = backStack.size > 1) { popBack() }

    // A notification tap (cold start or warm, see NotificationNavigationTarget's own kdoc)
    // replaces the whole back stack with its target rather than pushing onto whatever was
    // there before -- the same "land here directly, don't make him hunt for it" reasoning
    // the notification feature exists for in the first place.
    LaunchedEffect(NotificationNavigationTarget.route) {
        NotificationNavigationTarget.route?.let { route ->
            direction = 0
            backStack = listOf(route)
            NotificationNavigationTarget.route = null
        }
    }

    fun openMetric(metricId: String) = push("$METRIC_ROUTE_PREFIX$metricId")

    fun selectRoot(route: String) {
        if (backStack == listOf(route)) return
        direction = 0
        backStack = listOf(route)
    }

    fun openActivity(id: Long) = push("$ACTIVITY_ROUTE_PREFIX$id")

    fun openDayTimeline() = push(DAY_TIMELINE_ROUTE)

    fun openSlots() = push(SLOTS_ROUTE)

    fun openSlot(id: String) = push("$SLOT_ROUTE_PREFIX$id")

    fun openSettingsSection(sectionId: String) = push("$SETTINGS_SECTION_ROUTE_PREFIX$sectionId")

    /**
     * Called when [DayTimelineScreen] turns a selection into a new activity: the timeline
     * entry is replaced by the new activity's detail rather than pushed under it, so the
     * system back gesture from that detail screen returns straight to the Activités list,
     * not back through the timeline that only exists to create it.
     */
    fun replaceWithActivity(id: Long) {
        direction = 1
        backStack = backStack.dropLast(1) + "$ACTIVITY_ROUTE_PREFIX$id"
    }

    val colors = HelionThemeTokens.colors
    Scaffold(
        modifier = modifier,
        containerColor = colors.ground,
        contentWindowInsets = WindowInsets.systemBars,
        bottomBar = {
            HelionBottomBar(
                selected = rootOf(current),
                onSelect = { selectRoot(it.route) },
            )
        },
    ) { contentPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .consumeWindowInsets(contentPadding)
                .imePadding(),
        ) {
            AnimatedContent(
                targetState = current,
                transitionSpec = { screenTransition(direction) },
                label = "screen",
            ) { route ->
                Box(Modifier.fillMaxSize().background(colors.ground)) {
                    Destination(
                        route = route,
                        container = container,
                        onOpenMetric = ::openMetric,
                        onOpenSettings = { selectRoot(RootDestination.SETTINGS.route) },
                        onOpenSettingsSection = ::openSettingsSection,
                        onOpenActivity = ::openActivity,
                        onNewActivity = ::openDayTimeline,
                        onManageSlots = ::openSlots,
                        onOpenSlot = ::openSlot,
                        onActivityCreated = ::replaceWithActivity,
                        onBack = ::popBack,
                        onReset = { root ->
                            direction = 0
                            backStack = listOf(root)
                        },
                    )
                }
            }
        }
    }
}

/** The root tab a route belongs to, so the bar keeps its tab lit on pushed screens. */
private fun rootOf(route: String): RootDestination = when {
    route.startsWith(METRIC_ROUTE_PREFIX) -> RootDestination.HOME
    route.startsWith(ACTIVITY_ROUTE_PREFIX) || route == DAY_TIMELINE_ROUTE ||
        route == SLOTS_ROUTE || route.startsWith(SLOT_ROUTE_PREFIX) -> RootDestination.ACTIVITIES
    route.startsWith(SETTINGS_SECTION_ROUTE_PREFIX) -> RootDestination.SETTINGS
    else -> RootDestination.entries.firstOrNull { it.route == route } ?: RootDestination.HOME
}

/**
 * A push slides the new screen in from the right over a short distance while the old one
 * steps back; a pop reverses it; a tab switch is a quick cross-fade, since tabs are
 * siblings, not a hierarchy.
 */
private fun screenTransition(direction: Int): ContentTransform {
    val duration = HelionMotion.MEDIUM
    val easing = HelionMotion.Emphasized
    return when {
        direction > 0 ->
            (slideInHorizontally(tween(duration, easing = easing)) { it / 5 } + fadeIn(tween(duration, easing = easing))) togetherWith
                (slideOutHorizontally(tween(duration, easing = easing)) { -it / 12 } + fadeOut(tween(duration / 2)))
        direction < 0 ->
            (slideInHorizontally(tween(duration, easing = easing)) { -it / 12 } + fadeIn(tween(duration, easing = easing))) togetherWith
                (slideOutHorizontally(tween(duration, easing = easing)) { it / 5 } + fadeOut(tween(duration / 2)))
        else -> fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120))
    }
}

@Composable
private fun Destination(
    route: String,
    container: AppContainer,
    onOpenMetric: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSettingsSection: (String) -> Unit,
    onOpenActivity: (Long) -> Unit,
    onNewActivity: () -> Unit,
    onManageSlots: () -> Unit,
    onOpenSlot: (String) -> Unit,
    onActivityCreated: (Long) -> Unit,
    onBack: () -> Unit,
    onReset: (String) -> Unit,
) {
    when {
        route == RootDestination.SETTINGS.route -> SettingsScreen(container, onOpenSection = onOpenSettingsSection)

        route.startsWith(SETTINGS_SECTION_ROUTE_PREFIX) -> SettingsSectionScreen(
            container = container,
            sectionId = route.removePrefix(SETTINGS_SECTION_ROUTE_PREFIX),
            onBack = onBack,
        )

        route == RootDestination.SLEEP.route -> SleepScreen(container)

        route == RootDestination.ACTIVITIES.route -> ActivityListScreen(
            container = container,
            onOpenActivity = onOpenActivity,
            onNewActivity = onNewActivity,
            onManageSlots = onManageSlots,
        )

        route.startsWith(ACTIVITY_ROUTE_PREFIX) -> {
            val activityId = route.removePrefix(ACTIVITY_ROUTE_PREFIX).toLongOrNull()
            if (activityId != null) {
                ActivityDetailScreen(
                    container = container,
                    activityId = activityId,
                    onBack = onBack,
                    onDeleted = onBack,
                )
            } else {
                LaunchedEffect(route) { onReset(RootDestination.ACTIVITIES.route) }
            }
        }

        route == DAY_TIMELINE_ROUTE -> DayTimelineScreen(
            container = container,
            onBack = onBack,
            onActivityCreated = onActivityCreated,
        )

        route == SLOTS_ROUTE -> SlotListScreen(
            container = container,
            onBack = onBack,
            onOpenSlot = { onOpenSlot(it.toString()) },
            onNewSlot = { onOpenSlot(NEW_SLOT_ID) },
        )

        route.startsWith(SLOT_ROUTE_PREFIX) -> {
            val slotIdText = route.removePrefix(SLOT_ROUTE_PREFIX)
            SlotEditScreen(
                container = container,
                slotId = if (slotIdText == NEW_SLOT_ID) null else slotIdText.toLongOrNull(),
                onBack = onBack,
                onSaved = onBack,
                onDeleted = onBack,
            )
        }

        route.startsWith(METRIC_ROUTE_PREFIX) -> {
            val metric = MetricCatalog.byId(route.removePrefix(METRIC_ROUTE_PREFIX))
            if (metric != null) {
                MetricScreen(container = container, metric = metric, onBack = onBack)
            } else {
                // A saved id that no longer matches a catalog entry: fall back to Accueil
                // instead of a permanently broken detail screen. See MetricCatalog.byId's
                // kdoc -- the route survives process death too.
                LaunchedEffect(route) { onReset(RootDestination.HOME.route) }
            }
        }

        else -> HomeScreen(
            container = container,
            onOpenMetric = onOpenMetric,
            onOpenSettings = onOpenSettings,
        )
    }
}

/**
 * The tab bar, part of the screen rather than floating over it: the black ground, a hairline
 * along its top edge, linear icons with compact labels. The active tab is the accent blue
 * with a short bar on the top edge; inactive tabs recede to the tertiary text colour.
 */
@Composable
private fun HelionBottomBar(selected: RootDestination, onSelect: (RootDestination) -> Unit) {
    val colors = HelionThemeTokens.colors
    Column(modifier = Modifier.fillMaxWidth().background(colors.ground).navigationBarsPadding()) {
        HorizontalRule()
        Row(modifier = Modifier.fillMaxWidth().height(58.dp)) {
            RootDestination.entries.forEach { entry ->
                BottomBarItem(
                    label = stringResource(entry.labelRes),
                    icon = entry.icon,
                    selected = entry == selected,
                    onClick = { onSelect(entry) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BottomBarItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    val tint by animateColorAsState(if (selected) colors.accent else colors.textTertiary, tween(HelionMotion.SHORT), label = "tab")
    val indicator by animateFloatAsState(if (selected) 1f else 0f, tween(HelionMotion.SHORT), label = "tab-indicator")
    Box(
        modifier = modifier
            .fillMaxHeight()
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(20.dp)
                .height(2.dp)
                .alpha(indicator)
                .clip(RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp))
                .background(colors.accent),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Text(label, style = HelionType.labelSmall.copy(fontSize = 11.sp, lineHeight = 13.sp), color = tint)
        }
    }
}
