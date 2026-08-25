package ch.kevinjordil.helion.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.ui.metric.MetricCatalog
import ch.kevinjordil.helion.ui.metric.MetricReader
import ch.kevinjordil.helion.ui.metric.Range
import ch.kevinjordil.helion.ui.metric.Reading
import ch.kevinjordil.helion.ui.metric.formatValue
import ch.kevinjordil.helion.ui.minutesSinceLastSample
import ch.kevinjordil.helion.ui.quality.PersonalBaseline
import ch.kevinjordil.helion.ui.quality.computeBaseline
import ch.kevinjordil.helion.ui.quality.personalBaselineMessage
import ch.kevinjordil.helion.ui.quality.placeAgainstBaseline
import ch.kevinjordil.helion.ui.ribbon.DayRibbon
import ch.kevinjordil.helion.ui.ribbon.RibbonBar
import ch.kevinjordil.helion.ui.ribbon.buildRibbon
import ch.kevinjordil.helion.ui.ribbon.heroRibbonSize
import ch.kevinjordil.helion.ui.settings.SyncOutcome
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val HERO_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault())

/** How long Accueil's pull-to-refresh waits for Gadgetbridge's sync-finish broadcast. */
private const val SYNC_FINISH_TIMEOUT_MILLIS = 25_000L

private const val HEART_RATE_ID = "heart_rate"

/**
 * Accueil: heart rate as the hero, the other six catalog metrics as tiles, all sharing one
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
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshPhase by remember { mutableStateOf<RefreshPhase?>(null) }
    var banner by remember { mutableStateOf<RefreshBanner?>(null) }

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

    /**
     * [showSpinner] separates the two callers of the same refresh: a user's pull gesture
     * wants to see the pull-to-refresh indicator spin, but the automatic open-sync (see
     * the `LifecycleEventEffect` below) must stay invisible except for the freshness line's
     * phase text -- "show the existing data immediately... show the refresh in progress in
     * the freshness line", never a blocking spinner on every launch.
     */
    fun refresh(showSpinner: Boolean) {
        if (showSpinner) isRefreshing = true
        scope.launch {
            val outcome = performRefresh(
                onPhase = { phase -> refreshPhase = phase },
                requestSync = { container.commands.requestSync() },
                awaitSyncFinish = { trigger -> container.syncSignal.awaitSyncFinish(SYNC_FINISH_TIMEOUT_MILLIS, trigger) },
                copyToCache = { container.exportLocation.copyToCache() },
                // skipSyncRequest = true: this call already drove and awaited
                // ACTIVITY_SYNC_FINISH itself just above, so Ingestor must not request a
                // second, redundant sync right as the device's just finished -- see
                // Ingestor's kdoc on `skipSyncRequest`.
                ingest = { path -> container.ingestor.ingest(path, force = true, skipSyncRequest = true) },
            )
            banner = refreshBanner(outcome)
            if (outcome is SyncOutcome.Ingested) loadAll()
            refreshPhase = null
            if (showSpinner) isRefreshing = false
        }
    }

    /**
     * The open-sync's own loop (see [runOpenSyncLoop]): unlike [refresh], which is one
     * pass, this repeats passes -- each one built exactly like [refresh]'s single pass --
     * until a pass ingests nothing new or a cap is hit. Still invisible except for the
     * freshness line's phase text, same as before; [loadAll] and the banner are refreshed
     * after every individual pass, not just once at the end, so the screen visibly updates
     * as each pass lands rather than only when the whole loop finishes.
     */
    fun openSync() {
        scope.launch {
            runOpenSyncLoop(
                runPass = {
                    performRefresh(
                        onPhase = { phase -> refreshPhase = phase },
                        requestSync = { container.commands.requestSync() },
                        awaitSyncFinish = { trigger -> container.syncSignal.awaitSyncFinish(SYNC_FINISH_TIMEOUT_MILLIS, trigger) },
                        copyToCache = { container.exportLocation.copyToCache() },
                        ingest = { path -> container.ingestor.ingest(path, force = true, skipSyncRequest = true) },
                    )
                },
                onPass = { outcome ->
                    banner = refreshBanner(outcome)
                    if (outcome is SyncOutcome.Ingested) loadAll()
                },
            )
            refreshPhase = null
        }
    }

    LaunchedEffect(Unit) { loadAll() }

    // Sync on every ON_RESUME, not just the first composition: a `LaunchedEffect(Unit)`
    // here only ever fires once per composition lifetime, which covers a genuine cold
    // start but NOT the far more common "open the app" -- bringing an already-running
    // process back to the foreground from recents, which resumes the existing Activity
    // without recomposing Accueil from scratch, so a mount-only effect would silently
    // never run again for the rest of the process's life. ON_RESUME fires both on cold
    // start (the lifecycle replays it immediately since the observer attaches already-
    // resumed) and on every later foreground return, which is what "kicks off a sync
    // when the app opens" actually has to mean. OpenSyncGate's debounce (ten minutes) is
    // what keeps this from re-syncing on every quick foreground bounce.
    //
    // This is [openSync] (the repeating loop), not the single-pass [refresh]: an open is
    // exactly the moment worth catching all the way up, whereas pull-to-refresh stays one
    // pass -- it is a deliberate, watched gesture, not "make sure everything is current".
    // scope.launch (inside openSync) is tied to rememberCoroutineScope's composition
    // lifetime, so leaving Accueil for good (the Activity being torn down) cancels a
    // loop mid-flight the same way any other coroutine here already is.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val now = System.currentTimeMillis() / 1000
        if (exportConfigured && container.openSyncGate.shouldSync(now)) {
            container.openSyncGate.recordAttempt(now)
            openSync()
        }
    }

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
            onAction = { refresh(showSpinner = true) },
        )

        HomeStatus.Nominal -> PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { refresh(showSpinner = true) },
            modifier = modifier.fillMaxSize(),
        ) {
            val newestSample = latestByMetricId.values.filterNotNull().maxOfOrNull { it.timestamp }
            val tiles = MetricCatalog.all.filter { it.id != HEART_RATE_ID }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    HeroHeartRate(
                        latest = latestByMetricId[HEART_RATE_ID],
                        ribbonBars = buildRibbon(
                            dayReadingsByMetricId[HEART_RATE_ID].orEmpty(),
                            windowStart = System.currentTimeMillis() / 1000 - Range.DAY.seconds,
                            windowEnd = System.currentTimeMillis() / 1000,
                        ),
                        personalBaseline = latestByMetricId[HEART_RATE_ID]?.value?.let { value ->
                            placeAgainstBaseline(value, computeBaseline(monthReadingsByMetricId[HEART_RATE_ID].orEmpty()))
                        },
                        onClick = { onOpenMetric(HEART_RATE_ID) },
                    )
                }
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        val phaseRes = refreshPhase?.let(::refreshPhaseLabel)
                        Text(
                            phaseRes?.let { stringResource(it) } ?: freshnessLine(newestSample),
                            style = HelionType.bodySmall,
                            color = colors.textSecondary,
                        )
                        banner?.let { b ->
                            Text(
                                stringResource(b.messageRes, *b.args.toTypedArray()),
                                style = HelionType.bodySmall,
                                color = if (b.isAttention) colors.accentAmber else colors.textSecondary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                items(tiles.chunked(2)) { pair ->
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        pair.forEach { metric ->
                            val now = System.currentTimeMillis() / 1000
                            val tileLatest = latestByMetricId[metric.id]?.value
                            MetricTile(
                                metric = metric,
                                latestValue = tileLatest,
                                ribbonBars = buildRibbon(
                                    dayReadingsByMetricId[metric.id].orEmpty(),
                                    windowStart = now - Range.DAY.seconds,
                                    windowEnd = now,
                                ),
                                personalBaseline = tileLatest?.let { value ->
                                    placeAgainstBaseline(value, computeBaseline(monthReadingsByMetricId[metric.id].orEmpty()))
                                },
                                onClick = { onOpenMetric(metric.id) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (pair.size == 1) Box(modifier = Modifier.weight(1f)) {}
                    }
                }
                item { Spacer(Modifier.padding(bottom = 24.dp)) }
            }
        }
    }
}

@Composable
private fun HeroHeartRate(
    latest: Reading?,
    ribbonBars: List<RibbonBar>,
    personalBaseline: PersonalBaseline?,
    onClick: () -> Unit,
) {
    val colors = HelionThemeTokens.colors
    val metric = MetricCatalog.byId(HEART_RATE_ID) ?: return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // The most prominent element on the screen must not be the one dead tap
            // target: tapping the hero opens the same detail screen as tapping a tile,
            // with the same clickable mechanics (ripple via LocalIndication) and the
            // same Role.Button semantics for accessibility/switch access.
            .clickable(onClick = onClick, role = Role.Button)
            .padding(bottom = 8.dp),
    ) {
        DayRibbon(
            bars = ribbonBars,
            barColor = colors.accentViolet.copy(alpha = 0.35f),
            modifier = Modifier.heroRibbonSize(),
        )
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            if (latest != null) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(metric.formatValue(latest.value), style = HelionType.hero, color = colors.accentViolet)
                    Text(
                        stringResource(metric.unitRes),
                        style = HelionType.label,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }
                Text(
                    stringResource(R.string.accueil_hero_time, HERO_TIME_FORMAT.format(Instant.ofEpochSecond(latest.timestamp))),
                    style = HelionType.bodySmall,
                    color = colors.textSecondary,
                )
                personalBaseline?.let { baseline ->
                    val (messageRes, isAmber) = personalBaselineMessage(baseline)
                    Text(
                        stringResource(messageRes),
                        style = HelionType.bodySmall,
                        color = if (isAmber) colors.accentAmber else colors.textSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            } else {
                Text(stringResource(R.string.accueil_hero_no_reading), style = HelionType.body, color = colors.textSecondary)
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
        Text(stringResource(titleRes), style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
        Text(
            stringResource(bodyRes),
            style = HelionType.body,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        Button(onClick = onAction) { Text(stringResource(actionRes)) }
    }
}
