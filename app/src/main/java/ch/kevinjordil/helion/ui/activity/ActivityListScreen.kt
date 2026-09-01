package ch.kevinjordil.helion.ui.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.activity.ReanalysisOutcome
import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val ROW_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val ROW_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")
private val LAST_REANALYSIS_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())

/**
 * Activités: every recorded [Activity], grouped by the calendar day it starts on (most
 * recent day first, and within a day most recent first), plus two ways in: creating one
 * from a day's timeline, and managing the recurring slots step 3's detection will consume.
 *
 * A candidate's row never looks like a confirmed one's -- see [needsAttention] -- so the
 * owner's eye lands on what still needs a verdict before anything else on the screen.
 */
@Composable
fun ActivityListScreen(
    container: AppContainer,
    onOpenActivity: (Long) -> Unit,
    onNewActivity: () -> Unit,
    onManageSlots: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HelionThemeTokens.colors
    val scope = rememberCoroutineScope()
    var activities by remember { mutableStateOf<List<Activity>?>(null) }
    var lastFullReanalysis by remember { mutableStateOf<Long?>(null) }
    var reanalysisJob by remember { mutableStateOf<Job?>(null) }
    var reanalysisMessageRes by remember { mutableStateOf<Int?>(null) }
    var reanalysisMessageArgs by remember { mutableStateOf(emptyList<Any>()) }

    suspend fun loadActivities() {
        activities = container.database.activities().all()
    }

    LaunchedEffect(Unit) {
        loadActivities()
        lastFullReanalysis = container.database.syncState().get()?.lastFullDetectionRun
    }

    /**
     * Cancelling here means abandoning [ArchiveReanalyzer.reanalyze] between two of its own
     * bounded slices (see its kdoc) -- always safe, since every slice it already finished
     * committed its own overlap-checked inserts, and a later re-run (this action tapped
     * again) simply resumes covering the rest of the archive without duplicating anything
     * that slice already produced.
     */
    fun startReanalysis() {
        reanalysisMessageRes = null
        reanalysisJob = scope.launch {
            try {
                when (val outcome = container.archiveReanalyzer.reanalyze()) {
                    is ReanalysisOutcome.Completed -> {
                        reanalysisMessageRes = if (outcome.candidatesCreated > 0) {
                            reanalysisMessageArgs = listOf(outcome.candidatesCreated)
                            R.string.activity_reanalyze_result_found
                        } else {
                            R.string.activity_reanalyze_result_none
                        }
                        lastFullReanalysis = container.database.syncState().get()?.lastFullDetectionRun
                        loadActivities()
                    }
                    ReanalysisOutcome.AlreadyRunning -> reanalysisMessageRes = R.string.activity_reanalyze_already_running
                    ReanalysisOutcome.NothingStored -> reanalysisMessageRes = R.string.activity_reanalyze_result_none
                }
            } catch (e: CancellationException) {
                reanalysisMessageRes = R.string.activity_reanalyze_cancelled
            } finally {
                reanalysisJob = null
            }
        }
    }

    val loaded = activities

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.tab_activities).uppercase(), style = HelionType.label, color = colors.textSecondary)
            Text(
                stringResource(R.string.activity_manage_slots),
                style = HelionType.label,
                color = colors.accentViolet,
                modifier = Modifier.clickable(onClick = onManageSlots),
            )
        }

        Button(onClick = onNewActivity) {
            Text(stringResource(R.string.activity_new_action))
        }

        val running = reanalysisJob != null
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { startReanalysis() }, enabled = !running) {
                Text(stringResource(if (running) R.string.activity_reanalyzing else R.string.activity_reanalyze_action))
            }
            if (running) {
                Text(
                    stringResource(R.string.action_cancel),
                    style = HelionType.label,
                    color = colors.accentViolet,
                    modifier = Modifier.clickable { reanalysisJob?.cancel() },
                )
            }
        }
        reanalysisMessageRes?.let { resId ->
            Text(stringResource(resId, *reanalysisMessageArgs.toTypedArray()), style = HelionType.bodySmall, color = colors.textSecondary)
        }
        lastFullReanalysis?.let { timestamp ->
            Text(
                stringResource(R.string.activity_last_full_reanalysis, LAST_REANALYSIS_FORMAT.format(Instant.ofEpochSecond(timestamp))),
                style = HelionType.bodySmall,
                color = colors.textTertiary,
            )
        }

        when {
            loaded == null -> Unit

            loaded.isEmpty() -> EmptyActivityList(onNewActivity)

            else -> {
                val weekdayAbbreviations = stringArrayResource(R.array.weekday_short).toList()
                val groups = remember(loaded) { groupByDay(loaded) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    groups.forEach { (date, dayActivities) ->
                        item(key = "header:$date") {
                            Text(
                                dayHeaderText(date, weekdayAbbreviations),
                                style = HelionType.label,
                                color = colors.textTertiary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
                        items(dayActivities, key = { it.id }) { activity ->
                            ActivityRow(activity, onClick = { onOpenActivity(activity.id) })
                            HorizontalDivider(color = colors.divider)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyActivityList(onNewActivity: () -> Unit, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.activity_list_empty_title), style = HelionType.body, color = colors.textPrimary)
        Text(stringResource(R.string.activity_list_empty_body), style = HelionType.bodySmall, color = colors.textSecondary)
        Button(onClick = onNewActivity) {
            Text(stringResource(R.string.activity_list_empty_action))
        }
    }
}

@Composable
private fun ActivityRow(activity: Activity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    val attention = needsAttention(activity.status)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Weighted so a long title wraps within its own share of the row instead of
            // pushing the status label off the edge -- the status must always stay fully
            // visible, it is what tells a candidate from a decided activity. No
            // maxLines/ellipsis on the title itself: it wraps rather than clips. See
            // ActivityStatusWidthTest for the fixed-width status label's own coverage.
            Text(
                activity.title ?: sportOrNoneLabel(activity.sport),
                style = HelionType.body,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(statusLabelRes(activity.status)).uppercase(),
                style = HelionType.labelSmall,
                color = if (attention) colors.accentAmber else colors.textTertiary,
            )
        }
        // One composed string, not three Texts in a Row: three side-by-side, unweighted
        // Texts can overflow the row's own bounds at a narrow width or a large font scale
        // (exactly the clipping shape reported before). A single Text with no
        // maxLines/ellipsis wraps instead, the same "never clips" pattern the rest of the
        // app already uses for free-form copy.
        val range = "${ROW_TIME_FORMAT.format(Instant.ofEpochSecond(activity.startTimestamp))}" +
            "–${ROW_TIME_FORMAT.format(Instant.ofEpochSecond(activity.endTimestamp))}"
        Text(
            "${sportOrNoneLabel(activity.sport)} · $range · " +
                activityDurationText(activity.endTimestamp - activity.startTimestamp),
            style = HelionType.bodySmall,
            color = colors.textSecondary,
        )
    }
}

/** Groups [activities] by the local calendar day their start falls on, most recent day first. */
private fun groupByDay(activities: List<Activity>): List<Pair<LocalDate, List<Activity>>> {
    val zone = ZoneId.systemDefault()
    return activities
        .groupBy { Instant.ofEpochSecond(it.startTimestamp).atZone(zone).toLocalDate() }
        .toSortedMap(compareByDescending { it })
        .map { (date, group) -> date to group.sortedByDescending { it.startTimestamp } }
}

@Composable
private fun dayHeaderText(date: LocalDate, weekdayAbbreviations: List<String>): String {
    val today = LocalDate.now(ZoneId.systemDefault())
    return when (date) {
        today -> stringResource(R.string.activity_group_today)
        today.minusDays(1) -> stringResource(R.string.activity_group_yesterday)
        else -> "${weekdayAbbreviations[date.dayOfWeek.value - 1]} ${ROW_DATE_FORMAT.format(date)}"
    }
}
