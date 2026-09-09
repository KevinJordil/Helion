package ch.kevinjordil.helion.ui.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.store.Slot
import ch.kevinjordil.helion.store.SportType
import ch.kevinjordil.helion.ui.theme.HelionField
import ch.kevinjordil.helion.ui.theme.HelionFieldLabel
import ch.kevinjordil.helion.ui.theme.HelionScreenEdgeMargin
import ch.kevinjordil.helion.ui.theme.HelionCardSpacing
import ch.kevinjordil.helion.ui.theme.HelionSurface
import ch.kevinjordil.helion.ui.theme.HelionSurfacePadding
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import ch.kevinjordil.helion.ui.theme.HelionWarning
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.launch

private val SLOT_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** A card's own internal padding, the same rhythm every other screen's cards use. */

private fun parseSlotTime(text: String): Int? = try {
    LocalTime.parse(text.trim(), SLOT_TIME_FORMAT).toSecondOfDay()
} catch (e: DateTimeParseException) {
    null
}

private fun formatSlotTime(secondOfDay: Int): String = SLOT_TIME_FORMAT.format(LocalTime.ofSecondOfDay(secondOfDay.toLong()))

/**
 * Declares a new recurring [Slot] or edits an existing one -- label, sport, day of week,
 * start/end time and active/inactive -- plus delete. [slotId] null means "new"; a new slot
 * needs an explicit [R.string.slot_action_create] tap once its fields are valid (there is
 * nothing yet to autosave into), while editing an existing slot saves each valid field the
 * moment it changes, the same immediate-save pattern [ActivityDetailScreen] uses, so an edit
 * here is never silently lost either.
 *
 * Laid out on the same shared [HelionSurface]/[HelionField] tokens the activity edit screen
 * now uses: one card per field, except start and end, which share one card as the two ends
 * of the same slot.
 */
@Composable
fun SlotEditScreen(
    container: AppContainer,
    slotId: Long?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HelionThemeTokens.colors
    val scope = rememberCoroutineScope()

    var existing by remember(slotId) { mutableStateOf<Slot?>(null) }
    var loaded by remember(slotId) { mutableStateOf(slotId == null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(slotId) {
        if (slotId != null) {
            existing = container.database.slots().get(slotId)
            loaded = true
        }
    }

    var labelText by remember(slotId) { mutableStateOf("") }
    var sport by remember(slotId) { mutableStateOf(SportType.BADMINTON) }
    var dayOfWeek by remember(slotId) { mutableStateOf(DayOfWeek.MONDAY) }
    var startText by remember(slotId) { mutableStateOf("20:00") }
    var endText by remember(slotId) { mutableStateOf("22:00") }
    var active by remember(slotId) { mutableStateOf(true) }
    var initialisedFromExisting by remember(slotId) { mutableStateOf(false) }

    LaunchedEffect(existing) {
        val slot = existing
        if (slot != null && !initialisedFromExisting) {
            labelText = slot.label
            sport = slot.sport
            dayOfWeek = slot.dayOfWeek
            startText = formatSlotTime(slot.startSecondOfDay)
            endText = formatSlotTime(slot.endSecondOfDay)
            active = slot.active
            initialisedFromExisting = true
        }
    }

    val startSecond = parseSlotTime(startText)
    val endSecond = parseSlotTime(endText)
    val timeError = startSecond == null || endSecond == null

    fun saveExisting(mutate: (Slot) -> Slot) {
        val slot = existing ?: return
        val updated = mutate(slot)
        existing = updated
        scope.launch { container.database.slots().update(updated) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = HelionScreenEdgeMargin, vertical = HelionCardSpacing),
        verticalArrangement = Arrangement.spacedBy(HelionCardSpacing),
    ) {
        Text(
            stringResource(R.string.action_back),
            style = HelionType.label,
            color = colors.accentViolet,
            modifier = Modifier.clickable(onClick = onBack).padding(horizontal = HelionSurfacePadding),
        )

        if (loaded) {
            HelionSurface(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(HelionSurfacePadding)) {
                HelionField(
                    label = stringResource(R.string.slot_label_field),
                    value = labelText,
                    onValueChange = { text ->
                        labelText = text
                        if (slotId != null) saveExisting { it.copy(label = text) }
                    },
                )
            }

            HelionSurface(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(HelionSurfacePadding)) {
                HelionFieldLabel(stringResource(R.string.sport_picker_label))
                SportPicker(
                    selected = sport,
                    onSelect = {
                        sport = it
                        if (slotId != null) saveExisting { slot -> slot.copy(sport = it) }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }

            HelionSurface(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(HelionSurfacePadding)) {
                HelionFieldLabel(stringResource(R.string.slot_day_label))
                DayOfWeekPicker(
                    selected = dayOfWeek,
                    onSelect = {
                        dayOfWeek = it
                        if (slotId != null) saveExisting { slot -> slot.copy(dayOfWeek = it) }
                    },
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            // Start and end: the two ends of the same slot, so they share one card rather
            // than each getting its own -- the same pairing rule the activity edit screen
            // and Sommeil's own bedtime/wake row use.
            HelionSurface(
                modifier = Modifier.fillMaxWidth(),
                padding = PaddingValues(HelionSurfacePadding),
                verticalArrangement = Arrangement.spacedBy(HelionCardSpacing),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    HelionField(
                        label = stringResource(R.string.slot_start_label),
                        value = startText,
                        onValueChange = { text ->
                            startText = text
                            val newStart = parseSlotTime(text)
                            val newEnd = parseSlotTime(endText)
                            if (slotId != null && newStart != null && newEnd != null) {
                                saveExisting { slot -> slot.copy(startSecondOfDay = newStart, endSecondOfDay = newEnd) }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    HelionField(
                        label = stringResource(R.string.slot_end_label),
                        value = endText,
                        onValueChange = { text ->
                            endText = text
                            val newStart = parseSlotTime(startText)
                            val newEnd = parseSlotTime(text)
                            if (slotId != null && newStart != null && newEnd != null) {
                                saveExisting { slot -> slot.copy(startSecondOfDay = newStart, endSecondOfDay = newEnd) }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (timeError) {
                    HelionWarning(stringResource(R.string.slot_time_invalid))
                }
            }

            HelionSurface(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(HelionSurfacePadding)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.slot_active_label), style = HelionType.bodySmall, color = colors.textSecondary)
                    Switch(
                        checked = active,
                        onCheckedChange = { checked ->
                            active = checked
                            if (slotId != null) saveExisting { slot -> slot.copy(active = checked) }
                        },
                    )
                }
            }

            // Create/delete are actions, not measures, so they stay a plain button on the
            // page like every other action button in the app rather than riding on a card.
            Row(modifier = Modifier.padding(horizontal = HelionSurfacePadding)) {
                if (slotId == null) {
                    Button(
                        enabled = labelText.isNotBlank() && !timeError,
                        onClick = {
                            val start = startSecond ?: return@Button
                            val end = endSecond ?: return@Button
                            scope.launch {
                                container.database.slots().upsert(
                                    Slot(
                                        label = labelText,
                                        dayOfWeek = dayOfWeek,
                                        startSecondOfDay = start,
                                        endSecondOfDay = end,
                                        sport = sport,
                                        active = active,
                                    ),
                                )
                                onSaved()
                            }
                        },
                    ) {
                        Text(stringResource(R.string.slot_action_create))
                    }
                } else {
                    OutlinedButton(onClick = { showDeleteConfirm = true }) {
                        Text(stringResource(R.string.slot_action_delete))
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        val idToDelete = slotId
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.slot_delete_confirm_title)) },
            text = { Text(stringResource(R.string.slot_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    if (idToDelete != null) {
                        scope.launch {
                            container.database.slots().delete(idToDelete)
                            onDeleted()
                        }
                    }
                }) {
                    Text(stringResource(R.string.slot_action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun DayOfWeekPicker(selected: DayOfWeek, onSelect: (DayOfWeek) -> Unit, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    val weekdayAbbreviations = stringArrayResource(R.array.weekday_short).toList()
    LazyRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(DayOfWeek.entries.toList()) { day ->
            Text(
                weekdayAbbreviations[day.value - 1],
                style = HelionType.label,
                color = if (day == selected) colors.accentViolet else colors.textTertiary,
                modifier = Modifier
                    .clickable { onSelect(day) }
                    .padding(vertical = 4.dp),
            )
        }
    }
}
