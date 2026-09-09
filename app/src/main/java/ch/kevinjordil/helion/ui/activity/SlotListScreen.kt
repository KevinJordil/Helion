package ch.kevinjordil.helion.ui.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.store.Slot
import ch.kevinjordil.helion.ui.theme.HelionCardSpacing
import ch.kevinjordil.helion.ui.theme.HelionSurface
import ch.kevinjordil.helion.ui.theme.HelionSurfacePadding
import ch.kevinjordil.helion.ui.theme.HelionScreenEdgeMargin
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import java.time.LocalTime

/**
 * The root Column's own horizontal inset, matching [ActivityListScreen]'s own pattern now
 * that every slot sits on a raised [HelionSurface] instead of being separated by hairline
 * dividers alone -- see [HelionSurfacePadding]. The two still add up to the historical 20dp inset
 * `ActivityLabelWidthTest`'s slot active-state labels are measured against.
 */

/**
 * Every declared [Slot], in real calendar-week order (see [ch.kevinjordil.helion.store.SlotDao.all]'s
 * kdoc for the ordering defect this fixes), with entry points to create, edit, and -- inside
 * [SlotEditScreen] -- activate/deactivate or delete one. Step 3's detection reads exactly
 * this table, so nothing here proposes an activity; it only manages the declarations.
 */
@Composable
fun SlotListScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenSlot: (Long) -> Unit,
    onNewSlot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HelionThemeTokens.colors
    var slots by remember { mutableStateOf<List<Slot>?>(null) }

    LaunchedEffect(Unit) {
        slots = container.database.slots().all()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = HelionScreenEdgeMargin, vertical = HelionCardSpacing),
        verticalArrangement = Arrangement.spacedBy(HelionCardSpacing),
    ) {
        Text(
            stringResource(R.string.action_back),
            style = HelionType.label,
            color = colors.accentViolet,
            modifier = Modifier.clickable(onClick = onBack).padding(horizontal = HelionSurfacePadding),
        )
        Text(
            stringResource(R.string.activity_manage_slots),
            style = HelionType.headline,
            color = colors.textPrimary,
            modifier = Modifier.padding(horizontal = HelionSurfacePadding),
        )

        Button(onClick = onNewSlot, modifier = Modifier.padding(horizontal = HelionSurfacePadding)) {
            Text(stringResource(R.string.slot_new_action))
        }

        val loaded = slots
        when {
            loaded == null -> Unit

            loaded.isEmpty() -> Column(
                modifier = Modifier.padding(horizontal = HelionSurfacePadding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.slot_list_empty_title), style = HelionType.body, color = colors.textPrimary)
                Text(stringResource(R.string.slot_list_empty_body), style = HelionType.bodySmall, color = colors.textSecondary)
            }

            else -> {
                val weekdayAbbreviations = stringArrayResource(R.array.weekday_short).toList()
                LazyColumn {
                    item {
                        // Every slot now shares one raised surface instead of being
                        // separated only by hairline dividers -- see ActivityListScreen's
                        // own day groups, the same pattern.
                        HelionSurface(
                            modifier = Modifier.fillMaxWidth(),
                            padding = PaddingValues(HelionSurfacePadding),
                        ) {
                            loaded.forEachIndexed { index, slot ->
                                SlotRow(slot, weekdayAbbreviations, onClick = { onOpenSlot(slot.id) })
                                if (index != loaded.lastIndex) {
                                    HorizontalDivider(color = colors.divider.copy(alpha = 0.4f))
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
private fun SlotRow(slot: Slot, weekdayAbbreviations: List<String>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Weighted for the same reason as ActivityListScreen's row: a long label wraps
            // in its own share rather than crowding the active/suspended label off the row.
            Text(slot.label, style = HelionType.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Text(
                stringResource(if (slot.active) R.string.slot_active_on else R.string.slot_active_off),
                style = HelionType.labelSmall,
                color = if (slot.active) colors.textTertiary else colors.accentAmber,
            )
        }
        // One composed string rather than a Row of separate Texts -- see ActivityRow's own
        // kdoc for why: it wraps instead of risking an overflow-clip at a narrow width.
        val timeRange = "${LocalTime.ofSecondOfDay(slot.startSecondOfDay.toLong())}-" +
            LocalTime.ofSecondOfDay(slot.endSecondOfDay.toLong()).toString()
        Text(
            "${stringResource(sportLabelRes(slot.sport))} · ${weekdayAbbreviations[slot.dayOfWeek.value - 1]} $timeRange",
            style = HelionType.bodySmall,
            color = colors.textSecondary,
        )
    }
}
