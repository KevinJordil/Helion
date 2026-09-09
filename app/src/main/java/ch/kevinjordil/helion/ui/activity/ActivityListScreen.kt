package ch.kevinjordil.helion.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.ui.theme.HelionCardSpacing
import ch.kevinjordil.helion.ui.theme.HelionSurface
import ch.kevinjordil.helion.ui.theme.HelionSurfacePadding
import ch.kevinjordil.helion.ui.theme.HelionScreenEdgeMargin
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

    LaunchedEffect(Unit) {
        activities = container.database.activities().all()
    }

    val loaded = activities

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = HelionScreenEdgeMargin, vertical = HelionCardSpacing),
        verticalArrangement = Arrangement.spacedBy(HelionCardSpacing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = HelionSurfacePadding),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.tab_activities), style = HelionType.headline, color = colors.textPrimary)
            Text(
                stringResource(R.string.activity_manage_slots),
                style = HelionType.label,
                color = colors.accentViolet,
                modifier = Modifier.clickable(onClick = onManageSlots),
            )
        }

        Button(onClick = onNewActivity, modifier = Modifier.padding(horizontal = HelionSurfacePadding)) {
            Text(stringResource(R.string.activity_new_action))
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
                        item(key = "group:$date") {
                            // A day's own activities now share one raised surface instead
                            // of being separated by hairline dividers -- see HelionSurface's
                            // own kdoc. HelionSurfacePadding here (not HelionSurface's own wider
                            // default) is what keeps the total inset at the historical 20dp
                            // ActivityLabelWidthTest measures against.
                            HelionSurface(
                                modifier = Modifier.fillMaxWidth(),
                                padding = androidx.compose.foundation.layout.PaddingValues(HelionSurfacePadding),
                            ) {
                                dayActivities.forEachIndexed { index, activity ->
                                    ActivityRow(activity, onClick = { onOpenActivity(activity.id) })
                                    if (index != dayActivities.lastIndex) {
                                        androidx.compose.material3.HorizontalDivider(color = colors.divider.copy(alpha = 0.4f))
                                    }
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

/** The sport-colour bar down the left of an activity row. */
private val SPORT_BAR_WIDTH = 4.dp

/** The disc the sport glyph sits on. */
private val SPORT_DISC_SIZE = 40.dp

@Composable
private fun ActivityRow(activity: Activity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    val attention = needsAttention(activity.status)

    // The sport's own colour, carried as a bar down the left of the row rather than as a
    // wash behind it: a list of activities is a list of different sports, so one tint per
    // card would be a lie, and a full-card wash at this size reads as a slab anyway. The
    // bar is what makes a racket evening tell itself apart from a ride at a glance.
    val sportHue = colors.sportColorOrNull(activity.sport)

    Row(
        modifier = modifier
            .fillMaxWidth()
            // Intrinsic height, so the bar's fillMaxHeight has something to fill: in a Row
            // with an unbounded height constraint it would resolve to zero and the bar
            // would simply not be drawn.
            .height(IntrinsicSize.Min)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(SPORT_BAR_WIDTH)
                .fillMaxHeight()
                .clip(RoundedCornerShape(SPORT_BAR_WIDTH / 2))
                .background(sportHue ?: colors.divider),
        )
        // The sport's glyph, on a disc washed in its own colour: the row is read by shape
        // and colour before any of its words are.
        Box(
            modifier = Modifier
                .size(SPORT_DISC_SIZE)
                .clip(CircleShape)
                .background((sportHue ?: colors.divider).copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(sportEmoji(activity.sport), style = HelionType.body)
        }
        Column(
            modifier = Modifier.weight(1f),
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
                stringResource(statusLabelRes(activity.status)),
                style = HelionType.labelSmall,
                color = if (attention) colors.accentAmber else colors.textTertiary,
            )
        }
        // The duration is what the owner scans this list for, so it is set as a figure and
        // leads; the sport and the clock range follow as context. Still one composed Text
        // rather than three side by side: three unweighted Texts in a Row can overflow the
        // row's bounds at a narrow width or a large font scale (exactly the clipping shape
        // reported before), while one Text with no maxLines/ellipsis wraps instead.
        val range = "${ROW_TIME_FORMAT.format(Instant.ofEpochSecond(activity.startTimestamp))}" +
            "–${ROW_TIME_FORMAT.format(Instant.ofEpochSecond(activity.endTimestamp))}"
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                activityDurationText(activity.endTimestamp - activity.startTimestamp),
                style = HelionType.valueMedium,
                color = sportHue ?: colors.textPrimary,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                "${sportOrNoneLabel(activity.sport)} · $range",
                style = HelionType.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.alignByBaseline(),
            )
        }
        }
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
