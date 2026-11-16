package ch.kevinjordil.helion.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.ui.metric.Metric
import ch.kevinjordil.helion.ui.metric.MetricCatalog
import ch.kevinjordil.helion.ui.metric.MetricReader
import ch.kevinjordil.helion.ui.metric.Range
import ch.kevinjordil.helion.ui.metric.Reading
import ch.kevinjordil.helion.ui.minutesSinceLastSample
import ch.kevinjordil.helion.ui.quality.computeBaseline
import ch.kevinjordil.helion.ui.quality.placeAgainstBaseline
import ch.kevinjordil.helion.ui.theme.HelionButton
import ch.kevinjordil.helion.ui.theme.HelionControlRadius
import ch.kevinjordil.helion.ui.theme.HelionIcons
import ch.kevinjordil.helion.ui.theme.HelionMotion
import ch.kevinjordil.helion.ui.theme.HelionScreenEdgeMargin
import ch.kevinjordil.helion.ui.theme.HelionScreenHeader
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import ch.kevinjordil.helion.ui.theme.IconTile
import ch.kevinjordil.helion.ui.theme.StatusDot
import ch.kevinjordil.helion.ui.theme.helionWell
import ch.kevinjordil.helion.ui.theme.rememberReducedMotion
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The one metric with a goal set in Réglages -- see [ch.kevinjordil.helion.ui.settings.StepsGoal]. */
private const val STEPS_ID = "steps"

/** Buckets across the day for a line, and for bars. */
private const val DAY_LINE_BUCKETS = 36
private const val DAY_BAR_BUCKETS = 24

private const val WEEK_DAYS = 7
private const val WEEK_SECONDS = WEEK_DAYS * 24L * 3600

private val HEADER_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

/**
 * Accueil: a compact performance dashboard. All eight metrics share one two-column grid at
 * equal weight -- no hero, no global score -- so the whole day reads at a glance, above the
 * tab bar on a typical phone. Three states replace the grid entirely (see [HomeStatus]);
 * a failed refresh only changes the status line, since the data on screen is still valid.
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenMetric: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
    // while this screen was showing: not a stale "N minutes synchronisées" from a pass that
    // ran an hour ago, before the owner came back to Accueil.
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
        // baseline is defined over (see ui.quality.computeBaseline), and it also covers the
        // week a nightly metric's bars span.
        monthReadingsByMetricId = MetricCatalog.all.associate { metric ->
            metric.id to reader.load(metric, Range.MONTH, now).readings
        }
        loaded = true
    }

    /** Pull-to-refresh and the header button: the spinner shows until the pass ends. */
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
            // Read once per pass rather than per tile: it is a SharedPreferences value.
            val stepsGoal = container.stepsGoal.value
            val now = System.currentTimeMillis() / 1000

            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("home-dashboard"),
                contentPadding = PaddingValues(bottom = 14.dp),
            ) {
                item {
                    HelionScreenHeader(
                        title = stringResource(R.string.home_heading),
                        subtitle = {
                            HomeStatusLine(
                                phaseRes = refreshPhase?.let(::refreshPhaseLabel),
                                banner = banner,
                                newestSample = newestSample,
                            )
                        },
                        trailing = {
                            SyncButton(syncing = refreshPhase != null || isRefreshing, onClick = { refresh() })
                        },
                    )
                }
                itemsIndexed(MetricCatalog.all.chunked(2)) { rowIndex, pair ->
                    // Intrinsic height so the two tiles in a row match whatever they hold.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .padding(horizontal = HelionScreenEdgeMargin)
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pair.forEachIndexed { column, metric ->
                            val latest = latestByMetricId[metric.id]?.value
                            MetricTile(
                                metric = metric,
                                latestValue = latest,
                                chart = tileChart(
                                    metric = metric,
                                    latest = latest,
                                    day = dayReadingsByMetricId[metric.id].orEmpty(),
                                    month = monthReadingsByMetricId[metric.id].orEmpty(),
                                    stepsGoal = stepsGoal,
                                    now = now,
                                ),
                                chartCaption = when (tileSpec(metric).visual) {
                                    TileVisual.BARS_WEEK -> stringResource(R.string.tile_window_week)
                                    TileVisual.LINE_DAY, TileVisual.BARS_DAY -> stringResource(R.string.tile_window_day)
                                    TileVisual.GOAL -> null
                                },
                                personalBaseline = latest?.let { value ->
                                    placeAgainstBaseline(value, computeBaseline(monthReadingsByMetricId[metric.id].orEmpty()))
                                },
                                onClick = { onOpenMetric(metric.id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .entrance(index = rowIndex * 2 + column),
                            )
                        }
                        if (pair.size == 1) Box(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** The chart a tile draws, built from the readings already loaded for it. */
@Composable
private fun tileChart(metric: Metric, latest: Double?, day: List<Reading>, month: List<Reading>, stepsGoal: Int, now: Long): TileChart {
    val spec = tileSpec(metric)
    return when (spec.visual) {
        TileVisual.LINE_DAY -> TileChart.Line(tileSeries(day, now - Range.DAY.seconds, now, DAY_LINE_BUCKETS, spec))
        TileVisual.BARS_DAY -> TileChart.Bars(tileSeries(day, now - Range.DAY.seconds, now, DAY_BAR_BUCKETS, spec))
        TileVisual.BARS_WEEK -> TileChart.Bars(tileSeries(month, now - WEEK_SECONDS, now, WEEK_DAYS, spec))
        TileVisual.GOAL -> {
            val target = if (metric.id == STEPS_ID) stepsGoal else spec.target?.roundToInt() ?: 0
            val fraction = goalProgressFraction(latest, target) ?: return TileChart.None
            val percent = ((latest ?: 0.0) / target * 100).roundToInt()
            val caption = if (metric.id == STEPS_ID) {
                stringResource(R.string.goal_progress_compact, percent, target)
            } else {
                stringResource(R.string.tile_goal_target, percent, target)
            }
            TileChart.Goal(fraction, caption)
        }
    }
}

/**
 * One line under the title saying how fresh the screen is: a sync in progress, the outcome
 * of one that just finished, or the age of the newest value -- only one at a time, in that
 * order. The dot carries the state: blue while syncing, amber when a sync failed, neutral
 * otherwise.
 */
@Composable
private fun HomeStatusLine(phaseRes: Int?, banner: RefreshBanner?, newestSample: Long?) {
    val colors = HelionThemeTokens.colors
    val date = LocalDate.now().format(HEADER_DATE).replaceFirstChar { it.uppercase() }
    val status = when {
        phaseRes != null -> stringResource(phaseRes)
        banner != null -> stringResource(banner.messageRes, *banner.args.toTypedArray())
        else -> {
            val minutes = minutesSinceLastSample(newestSample, System.currentTimeMillis() / 1000)
            if (minutes == null) stringResource(R.string.never_synced) else stringResource(R.string.home_freshness, minutes)
        }
    }
    val attention = phaseRes == null && banner?.isAttention == true
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(date, style = HelionType.bodySmall, color = colors.textSecondary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusDot(
                when {
                    phaseRes != null -> colors.accent
                    attention -> colors.attention
                    else -> colors.textTertiary
                },
            )
            Text(status, style = HelionType.labelSmall, color = if (attention) colors.attention else colors.textTertiary)
        }
    }
}

/** The header's discreet sync control; its glyph turns only while a pass is running. */
@Composable
private fun SyncButton(syncing: Boolean, onClick: () -> Unit) {
    val colors = HelionThemeTokens.colors
    val reduced = rememberReducedMotion()
    val angle = if (syncing && !reduced) {
        val transition = rememberInfiniteTransition(label = "sync")
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "sync-angle").value
    } else {
        0f
    }
    IconButton(onClick = onClick, enabled = !syncing) {
        Box(modifier = Modifier.size(38.dp).helionWell(HelionControlRadius), contentAlignment = Alignment.Center) {
            Icon(
                HelionIcons.Refresh,
                contentDescription = stringResource(R.string.home_sync_action),
                tint = if (syncing) colors.accent else colors.textSecondary,
                modifier = Modifier.size(18.dp).rotate(angle),
            )
        }
    }
}

/** Tiles rise into place one after another on first display, quickly; never with motion off. */
@Composable
private fun Modifier.entrance(index: Int): Modifier {
    val reduced = rememberReducedMotion()
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced && progress.value < 1f) {
            delay(index * 28L)
            progress.animateTo(1f, tween(HelionMotion.MEDIUM + 80, easing = HelionMotion.Emphasized))
        }
    }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 10.dp.toPx()
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
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        IconTile(HelionIcons.Link, tint = colors.accent, size = 44.dp, iconSize = 22.dp)
        Text(
            stringResource(titleRes),
            style = HelionType.headline,
            color = colors.textPrimary,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            stringResource(bodyRes),
            style = HelionType.body,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 8.dp, bottom = 22.dp),
        )
        HelionButton(onClick = onAction) { Text(stringResource(actionRes)) }
    }
}
