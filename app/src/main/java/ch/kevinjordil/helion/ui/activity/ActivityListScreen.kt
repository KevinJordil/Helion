package ch.kevinjordil.helion.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.store.ActivityStatus
import ch.kevinjordil.helion.ui.theme.HelionButton
import ch.kevinjordil.helion.ui.theme.HelionCardSpacing
import ch.kevinjordil.helion.ui.theme.HelionIcons
import ch.kevinjordil.helion.ui.theme.HelionOutlinedButton
import ch.kevinjordil.helion.ui.theme.HelionScreenEdgeMargin
import ch.kevinjordil.helion.ui.theme.HelionScreenHeader
import ch.kevinjordil.helion.ui.theme.HelionSectionLabel
import ch.kevinjordil.helion.ui.theme.HelionSurface
import ch.kevinjordil.helion.ui.theme.HelionSurfacePadding
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import ch.kevinjordil.helion.ui.theme.HorizontalRule
import ch.kevinjordil.helion.ui.theme.IconTile
import ch.kevinjordil.helion.ui.theme.StatusDot
import ch.kevinjordil.helion.ui.theme.helionRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ROW_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val ROW_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")

/**
 * Activités: the sport history, newest first, grouped by day. Each day is one card with
 * its sessions as compact rows -- the sport's glyph in its colour, the title, the sport and
 * time range, and on the right the duration and status -- so a week of sessions can be
 * compared at a glance. A candidate still waiting for a decision is the one row marked in
 * the attention colour.
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
    var activities by remember { mutableStateOf<List<Activity>?>(null) }

    // A sync can bring new candidates in while this list is open (the opening sync, or a
    // manual one from Réglages); reload whenever one lands.
    val syncState by container.foregroundSync.state.collectAsState()
    LaunchedEffect(syncState.completedPasses) {
        activities = container.database.activities().all()
    }

    val loaded = activities
    val weekdayAbbreviations = stringArrayResource(R.array.weekday_short).toList()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(HelionCardSpacing),
    ) {
        item {
            HelionScreenHeader(title = stringResource(R.string.tab_activities))
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = HelionScreenEdgeMargin),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HelionButton(onClick = onNewActivity, icon = HelionIcons.Plus, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.activity_new_action), style = HelionType.label)
                }
                HelionOutlinedButton(onClick = onManageSlots, icon = HelionIcons.Calendar, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.activity_manage_slots), style = HelionType.label)
                }
            }
        }
        when {
            loaded == null -> Unit
            loaded.isEmpty() -> item { EmptyActivityList(onNewActivity) }
            else -> {
                val groups = groupByDay(loaded)
                groups.forEach { (date, dayActivities) ->
                    item(key = "day:$date") {
                        Column(
                            modifier = Modifier.padding(horizontal = HelionScreenEdgeMargin).padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val totalSeconds = dayActivities.sumOf { it.endTimestamp - it.startTimestamp }
                            HelionSectionLabel(dayHeaderText(date, weekdayAbbreviations)) {
                                Text(activityDurationText(totalSeconds), style = HelionType.labelSmall, color = colors.textTertiary)
                            }
                            HelionSurface(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
                                dayActivities.forEachIndexed { index, activity ->
                                    if (index > 0) HorizontalRule(Modifier.padding(start = 66.dp))
                                    ActivityRow(activity, onClick = { onOpenActivity(activity.id) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyActivityList(onNewActivity: () -> Unit) {
    val colors = HelionThemeTokens.colors
    HelionSurface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = HelionScreenEdgeMargin).padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconTile(HelionIcons.Stopwatch, tint = colors.accent, size = 40.dp, iconSize = 20.dp)
        Text(stringResource(R.string.activity_list_empty_title), style = HelionType.title, color = colors.textPrimary)
        Text(stringResource(R.string.activity_list_empty_body), style = HelionType.bodySmall, color = colors.textSecondary)
        HelionButton(onClick = onNewActivity, icon = HelionIcons.Plus) {
            Text(stringResource(R.string.activity_list_empty_action))
        }
    }
}

@Composable
private fun ActivityRow(activity: Activity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    val attention = needsAttention(activity.status)
    val sportHue = colors.sportColorOrNull(activity.sport) ?: colors.textSecondary
    val range = ROW_TIME_FORMAT.format(Instant.ofEpochSecond(activity.startTimestamp)) +
        "–" + ROW_TIME_FORMAT.format(Instant.ofEpochSecond(activity.endTimestamp))

    Row(
        modifier = modifier
            .helionRow(onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = HelionSurfacePadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(sportIcon(activity.sport), tint = sportHue, size = 40.dp, iconSize = 20.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                activity.title ?: sportOrNoneLabel(activity.sport),
                style = HelionType.label.copy(fontSize = HelionType.body.fontSize, fontWeight = HelionType.title.fontWeight),
                color = colors.textPrimary,
            )
            Text("${sportOrNoneLabel(activity.sport)} · $range", style = HelionType.bodySmall, color = colors.textSecondary)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(activityDurationText(activity.endTimestamp - activity.startTimestamp), style = HelionType.valueSmall, color = colors.textPrimary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                StatusDot(statusColor(activity.status, attention))
                Text(
                    stringResource(statusLabelRes(activity.status)),
                    style = HelionType.labelSmall,
                    color = if (attention) colors.attention else colors.textTertiary,
                )
            }
        }
    }
}

/** The dot beside a status: amber while undecided, blue once published, quiet otherwise. */
@Composable
private fun statusColor(status: ActivityStatus, attention: Boolean) = HelionThemeTokens.colors.let { colors ->
    when {
        attention -> colors.attention
        status == ActivityStatus.PUBLISHED -> colors.accent
        status == ActivityStatus.CONFIRMED -> colors.positive
        else -> colors.textTertiary
    }
}

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
