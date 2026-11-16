package ch.kevinjordil.helion.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.ui.metric.MetricCatalog
import ch.kevinjordil.helion.ui.metric.MetricReader
import ch.kevinjordil.helion.ui.metric.Range
import ch.kevinjordil.helion.ui.metric.Reading
import ch.kevinjordil.helion.ui.metric.TileStrand
import ch.kevinjordil.helion.ui.metric.metricColor
import ch.kevinjordil.helion.ui.minutesSinceLastSample
import ch.kevinjordil.helion.ui.quality.computeBaseline
import ch.kevinjordil.helion.ui.quality.placeAgainstBaseline
import ch.kevinjordil.helion.ui.ribbon.buildRibbon
import ch.kevinjordil.helion.ui.theme.HelionContentInset
import ch.kevinjordil.helion.ui.theme.HelionScreenEdgeMargin
import ch.kevinjordil.helion.ui.theme.HelionSurface
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch


/** The one metric with a goal to measure a day against -- see [ch.kevinjordil.helion.ui.settings.StepsGoal]. */
private const val STEPS_ID = "steps"

/**
 * How many bars a day is cut into. Far fewer than the 48 half-hour buckets this used to
 * draw: at that density a tile's strand was a rake of hairlines, which is what made the
 * screen read as an instrument panel rather than as something pleasant to look at. Coarser
 * bars still show the day's shape -- when he moved, when he slept -- which is all a tile's
 * strand was ever for; the detail chart is where the actual resolution lives.
 */
private const val TILE_RIBBON_BUCKETS = 20

/** One bar per day for a [TileStrand.WEEK] metric: night to night is the shape that means something. */
private const val WEEK_RIBBON_BUCKETS = 7

private const val WEEK_SECONDS = 7L * 24 * 3600

/**
 * Accueil: all eight metrics in a compact two-column grid, all sharing one
 * day ribbon. Three states replace the dashboard entirely -- see [HomeStatus] -- and a
 * fourth, quieter one (a failed refresh) overlays a banner without touching data already
 * on screen, because that data is still valid.
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenMetric: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HelionThemeTokens.colors
    val reader = remember(container) { MetricReader(container.database) }
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var latestByMetricId by remember { mutableStateOf<Map<String, Reading?>>(emptyMap()) }
    var dayReadingsByMetricId by remember { mutableStateOf<Map<String, List<Reading>>>(emptyMap()) }
    var monthReadingsByMetricId by remember { mutableStateOf<Map<String, List<Reading>>>(emptyMap()) }
    // The sync itself lives on AppContainer (see ForegroundSync): started when the app comes
    // to the foreground, wherever it lands, and not cancelled by leaving this screen. This
    // screen only shows its progress and reloads when a pass lands.
    val syncState by container.foregroundSync.state.collectAsState()
    // A completed pass' outcome replaces the freshness line only for passes that finished
    // while this screen was showing, as before: not a stale "N minutes synchronisées" from a
    // pass that ran an hour ago, before the owner came back to Accueil.
    val passesAtMount = remember { container.foregroundSync.state.value.completedPasses }
    val banner = syncState.lastOutcome
        ?.takeIf { syncState.completedPasses > passesAtMount }
        ?.let(::refreshBanner)
    val refreshPhase = syncState.phase
    var isRefreshing by remember { mutableStateOf(false) }

    val exportConfigured = container.exportLocation.uri != null

    suspend fun loadAll() {
        val now = System.currentTimeMillis() / 1000
        latestByMetricId = MetricCatalog.all.associate { metric ->
            metric.id to reader.load(metric, Range.YEAR, now).latest
        }
        dayReadingsByMetricId = MetricCatalog.all.associate { metric ->
            metric.id to reader.load(metric, Range.DAY, now).readings
        }
        // Range.MONTH is exactly the "roughly the last 30 days" window the personal
        // baseline is defined over (see ui.quality.computeBaseline); reusing it here is
        // the same list MetricReader already computes for the detail screen's Mois range.
        monthReadingsByMetricId = MetricCatalog.all.associate { metric ->
            metric.id to reader.load(metric, Range.MONTH, now).readings
        }
        loaded = true
    }

    /** Pull-to-refresh: the spinner shows until the pass (a new one, or one already running) ends. */
    fun refresh() {
        isRefreshing = true
        scope.launch {
            try {
                container.foregroundSync.syncNow().await()
            } finally {
                isRefreshing = false
            }
        }
    }

    // Keyed on the completed-pass counter: the first composition loads what is stored, and
    // every pass that lands afterwards -- the opening sync included -- reloads it.
    LaunchedEffect(syncState.completedPasses) { loadAll() }

    if (!loaded) return

    val hasAnyStoredSample = latestByMetricId.values.any { it != null }
    when (resolveHomeStatus(exportConfigured, hasAnyStoredSample)) {
        HomeStatus.NoSource -> EmptyState(
            modifier = modifier,
            titleRes = R.string.accueil_no_source_title,
            bodyRes = R.string.accueil_no_source_body,
            actionRes = R.string.accueil_no_source_action,
            onAction = onOpenSettings,
        )

        HomeStatus.EmptyArchive -> EmptyState(
            modifier = modifier,
            titleRes = R.string.accueil_empty_title,
            bodyRes = R.string.accueil_empty_body,
            actionRes = R.string.accueil_empty_action,
            onAction = { refresh() },
        )

        HomeStatus.Nominal -> PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { refresh() },
            modifier = modifier.fillMaxSize(),
        ) {
            val newestSample = latestByMetricId.values.filterNotNull().maxOfOrNull { it.timestamp }
            val tiles = MetricCatalog.all
            // Read once per pass rather than per tile: it is a SharedPreferences value.
            val stepsGoal = container.stepsGoal.value

            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("home-dashboard"),
                contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp),
            ) {
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = HelionContentInset).padding(bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(stringResource(R.string.home_heading), style = HelionType.headline, color = colors.textPrimary)
                        Text(
                            LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)),
                            style = HelionType.bodySmall,
                            color = colors.textSecondary,
                        )
                    }
                }
                item {
                    // One line, not the four the owner reported ("Reçue à…" / "Comme
                    // d'habitude" / a blank line from stacked padding / "Dernière valeur
                    // reçue…"): a refresh in progress, a completed pass's outcome, and the
                    // ordinary freshness line all describe the same thing -- how fresh and
                    // reliable what is on screen right now is -- so only one of them is
                    // ever shown at a time, picked in that priority order. The amber state
                    // for a failed or degraded refresh survives as this line's colour,
                    // rather than a second banner line underneath it. The distinct,
                    // genuinely different fact -- the personal baseline -- stays inside each tile.
                    val phaseRes = refreshPhase?.let(::refreshPhaseLabel)
                    val currentBanner = banner
                    val text = when {
                        phaseRes != null -> stringResource(phaseRes)
                        currentBanner != null -> stringResource(currentBanner.messageRes, *currentBanner.args.toTypedArray())
                        else -> freshnessLine(newestSample)
                    }
                    val isAmber = phaseRes == null && currentBanner?.isAttention == true
                    Text(
                        text,
                        style = HelionType.bodySmall,
                        color = if (isAmber) colors.accentAmber else colors.textSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = HelionContentInset, end = HelionContentInset, top = 0.dp, bottom = 6.dp),
                    )
                }
                items(tiles.chunked(2)) { pair ->
                    // Each metric is its own idea, so each tile sits on its own raised
                    // surface (see HelionSurface's own kdoc) rather than two unrelated
                    // metrics sharing one rectangle; a row here only lays two of those
                    // surfaces out side by side. The row's own margin is
                    // HelionScreenEdgeMargin and each tile pads by HelionSurfacePadding,
                    // so a tile's text starts on HelionContentInset -- the same line as the
                    // heading and freshness line above it, which is what a tighter tile
                    // padding used to break.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Intrinsic height so the two tiles in a row are the same
                            // height whatever they hold: only `steps` carries a goal
                            // track, and without this its card stood taller than its
                            // neighbour and the grid stopped looking like a grid.
                            .height(IntrinsicSize.Min)
                            .padding(horizontal = HelionScreenEdgeMargin, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        pair.forEach { metric ->
                            val now = System.currentTimeMillis() / 1000
                            val tileLatest = latestByMetricId[metric.id]?.value
                            HelionSurface(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                tint = colors.metricColor(metric),
                                padding = PaddingValues(0.dp),
                            ) {
                                // Only `steps` has a goal today, and it is the only tile
                                // that gets a track; see MetricTile's own kdoc.
                                val goalFraction = if (metric.id == STEPS_ID) {
                                    goalProgressFraction(tileLatest, stepsGoal)
                                } else {
                                    null
                                }
                                MetricTile(
                                    metric = metric,
                                    latestValue = tileLatest,
                                    goalProgress = goalFraction,
                                    goalCaption = goalFraction?.let {
                                        stringResource(
                                            R.string.goal_progress_compact,
                                            (it * 100).roundToInt(),
                                            stepsGoal,
                                        )
                                    },
                                    // The strand's window follows the metric's own rhythm
                                    // rather than always being "today" -- see TileStrand.
                                    ribbonBars = when (metric.tileStrand) {
                                        TileStrand.NONE -> emptyList()
                                        TileStrand.DAY -> buildRibbon(
                                            dayReadingsByMetricId[metric.id].orEmpty(),
                                            windowStart = now - Range.DAY.seconds,
                                            windowEnd = now,
                                            bucketCount = TILE_RIBBON_BUCKETS,
                                        )
                                        TileStrand.WEEK -> buildRibbon(
                                            monthReadingsByMetricId[metric.id].orEmpty(),
                                            windowStart = now - WEEK_SECONDS,
                                            windowEnd = now,
                                            bucketCount = WEEK_RIBBON_BUCKETS,
                                        )
                                    },
                                    strandCaption = if (metric.tileStrand == TileStrand.WEEK) {
                                        stringResource(R.string.tile_strand_week_compact)
                                    } else {
                                        null
                                    },
                                    personalBaseline = tileLatest?.let { value ->
                                        placeAgainstBaseline(value, computeBaseline(monthReadingsByMetricId[metric.id].orEmpty()))
                                    },
                                    onClick = { onOpenMetric(metric.id) },
                                    modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                                )
                            }
                        }
                        if (pair.size == 1) Box(modifier = Modifier.weight(1f)) {}
                    }
                }
            }
        }
    }
}

@Composable
private fun freshnessLine(newestSampleTimestamp: Long?): String {
    val minutes = minutesSinceLastSample(newestSampleTimestamp, System.currentTimeMillis() / 1000)
    return if (minutes == null) {
        stringResource(R.string.never_synced)
    } else {
        stringResource(R.string.last_value_minutes_ago, minutes)
    }
}

@Composable
private fun EmptyState(
    titleRes: Int,
    bodyRes: Int,
    actionRes: Int,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HelionThemeTokens.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(titleRes), style = HelionType.headline, color = colors.textPrimary)
        Text(
            stringResource(bodyRes),
            style = HelionType.body,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        Button(onClick = onAction) { Text(stringResource(actionRes)) }
    }
}
