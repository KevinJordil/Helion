package ch.kevinjordil.helion.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.ui.theme.HelionCardSpacing
import ch.kevinjordil.helion.ui.theme.HelionScreenEdgeMargin
import ch.kevinjordil.helion.ui.theme.HelionSurface
import ch.kevinjordil.helion.ui.theme.HelionSurfacePadding
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ROW_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val ROW_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")

/**
 * The root Column's own horizontal inset, now that each day's group of activities sits on a
 * raised [HelionSurface] -- see [HelionSurfacePadding]. The two still add up to the historical 20dp
 * inset [ActivityLabelWidthTest] measures against.
 */

/**
 * Activités: every recorded [Activity], grouped by the calendar day it starts on (most
 * recent day first, and within a day most recent first), plus two ways in: creating one
 * from a day's timeline, and managing the recurring slots step 3's detection will consume.
 *
 * A candidate's row never looks like a confirmed one's -- see [needsAttention] -- so the
 * owner's eye lands on what still needs a verdict before anything else on the screen.
 */
@OptIn(ExperimentalLayoutApi::class)
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

    // Reloaded whenever a foreground sync lands (see ForegroundSync): a candidate detected by
    // the opening sync shows up here without leaving and re-entering the screen.
    val syncState by container.foregroundSync.state.collectAsState()
    LaunchedEffect(syncState.completedPasses) {
        activities = container.database.activities().all()
    }

    val loaded = activities

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = HelionScreenEdgeMargin, vertical = HelionCardSpacing),
        verticalArrangement = Arrangement.spacedBy(HelionCardSpacing),
    ) {
        Text(
            stringResource(R.string.tab_activities), style = HelionType.headline, color = colors.textPrimary,
            modifier = Modifier.padding(horizontal = HelionSurfacePadding),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = HelionSurfacePadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onNewActivity) {
                Text(stringResource(R.string.activity_new_action))
            }
            OutlinedButton(onClick = onManageSlots) {
                Text(stringResource(R.string.activity_manage_slots))
            }
        }
        when {
            loaded == null -> Unit

            loaded.isEmpty() -> EmptyActivityList(onNewActivity, modifier = Modifier.padding(horizontal = HelionSurfacePadding))

            else -> {
                val weekdayAbbreviations = stringArrayResource(R.array.weekday_short).toList()
                val groups = remember(loaded) { groupByDay(loaded) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(HelionCardSpacing)) {
                    groups.forEach { (date, dayActivities) ->
                        item(key = "header:$date") {
                            Text(
                                dayHeaderText(date, weekdayAbbreviations),
                                style = HelionType.label,
                                color = colors.textTertiary,
                                modifier = Modifier.padding(start = HelionSurfacePadding, end = HelionSurfacePadding, bottom = 4.dp),
                            )
                        }
                        // One card per activity, washed in its own sport's colour, rather
                        // than one neutral card per day holding divider-separated rows. A
                        // day is not one thing -- a ride and a badminton evening have
                        // nothing to do with each other beyond falling on the same date --
                        // and the day header already says which day these are. What the
                        // shared card did buy was a compact list; the tinted cards are
                        // what make this screen read as a sports app rather than a ledger.
                        items(dayActivities, key = { "activity:${it.id}" }) { activity ->
                            HelionSurface(
                                modifier = Modifier.fillMaxWidth(),
                                padding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                tint = colors.sportColorOrNull(activity.sport),
                            ) {
                                ActivityRow(activity, onClick = { onOpenActivity(activity.id) })
                            }
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

/** The disc the sport glyph sits on. */
private val SPORT_DISC_SIZE = 40.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivityRow(activity: Activity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    val attention = needsAttention(activity.status)

    // The sport's own colour: the card behind this row is already washed in it (see the
    // list's own items block), and the glyph disc and the duration pick it up again so the
    // row is readable as that sport even at a glance.
    val sportHue = colors.sportColorOrNull(activity.sport)

    Row(
        modifier = modifier.fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Button)
            .padding(HelionSurfacePadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(SPORT_DISC_SIZE).clip(RoundedCornerShape(14.dp))
                .background((sportHue ?: colors.divider).copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(sportEmoji(activity.sport), style = HelionType.body)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(activity.title ?: sportOrNoneLabel(activity.sport),
                style = HelionType.title, color = colors.textPrimary)
            val range = "${ROW_TIME_FORMAT.format(Instant.ofEpochSecond(activity.startTimestamp))}" +
                "–${ROW_TIME_FORMAT.format(Instant.ofEpochSecond(activity.endTimestamp))}"
            Text("${sportOrNoneLabel(activity.sport)} · $range",
                style = HelionType.bodySmall, color = colors.textSecondary)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(activityDurationText(activity.endTimestamp - activity.startTimestamp),
                    style = HelionType.valueMedium, color = sportHue ?: colors.textPrimary)
                Text(
                    stringResource(statusLabelRes(activity.status)),
                    style = HelionType.labelSmall,
                    color = if (attention) colors.accentAmber else colors.textSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (attention) colors.accentAmber.copy(alpha = 0.12f) else colors.surfaceRaised)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
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
