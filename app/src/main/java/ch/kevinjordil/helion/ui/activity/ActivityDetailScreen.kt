package ch.kevinjordil.helion.ui.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.calorie.ActivityCalorieEstimate
import ch.kevinjordil.helion.calorie.estimateActivityCalories
import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.store.ActivityStatus
import ch.kevinjordil.helion.store.Publication
import ch.kevinjordil.helion.store.PublicationState
import ch.kevinjordil.helion.store.PublicationTarget
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One activity, fully editable: title, sport, notes, start and end, plus delete and the
 * three status transitions the owner needs (confirm a candidate, dismiss it, reopen a
 * dismissed one -- see [ActivityStatus]'s own kdoc for why nothing else moves the status).
 *
 * Every field saves itself the moment it holds a valid value -- there is no separate "Save"
 * step to forget, the same pattern [ch.kevinjordil.helion.ui.settings.SettingsScreen]'s
 * steps-goal field already uses -- so an edit is never silently lost by navigating away
 * mid-thought. The one field that can be genuinely invalid mid-edit, start/end text, shows
 * its own inline note instead of being saved or silently reverted while it is invalid: what
 * is on screen is always either what is stored, or an explicit "not yet applied" state.
 */
@Composable
fun ActivityDetailScreen(
    container: AppContainer,
    activityId: Long,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HelionThemeTokens.colors
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }

    var activity by remember(activityId) { mutableStateOf<Activity?>(null) }
    var loadedOnce by remember(activityId) { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var customServerPublication by remember(activityId) { mutableStateOf<Publication?>(null) }
    var sendingToCustomServer by remember(activityId) { mutableStateOf(false) }
    var calorieEstimate by remember(activityId) { mutableStateOf<ActivityCalorieEstimate?>(null) }

    suspend fun reloadCustomServerPublication() {
        customServerPublication = container.database.publications().get(activityId, PublicationTarget.CUSTOM_SERVER)
    }

    LaunchedEffect(activityId) {
        val loaded = container.database.activities().get(activityId)
        activity = loaded
        reloadCustomServerPublication()
        if (loaded != null) {
            val samples = withContext(Dispatchers.IO) {
                container.database.minuteSamples().between(loaded.startTimestamp, loaded.endTimestamp)
            }
            calorieEstimate = estimateActivityCalories(container.profile, loaded.startTimestamp, zone, samples)
        }
        loadedOnce = true
    }

    fun sendToCustomServer() {
        if (sendingToCustomServer) return
        sendingToCustomServer = true
        scope.launch {
            withContext(Dispatchers.IO) { container.customServerPublisher.send(activityId) }
            reloadCustomServerPublication()
            sendingToCustomServer = false
        }
    }

    fun save(updated: Activity) {
        activity = updated
        scope.launch { container.database.activities().update(updated) }
    }

    val current = activity

    if (current == null) {
        Column(modifier = modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BackLink(onBack)
            if (loadedOnce) {
                Text(stringResource(R.string.activity_not_found), style = HelionType.body, color = colors.textSecondary)
            }
        }
        return
    }

    var titleText by remember(activityId) { mutableStateOf(current.title.orEmpty()) }
    var notesText by remember(activityId) { mutableStateOf(current.notes.orEmpty()) }
    var startText by remember(activityId) { mutableStateOf(formatActivityDateTime(current.startTimestamp, zone)) }
    var endText by remember(activityId) { mutableStateOf(formatActivityDateTime(current.endTimestamp, zone)) }

    fun applyStartEnd(newStartText: String, newEndText: String) {
        val start = parseActivityDateTime(newStartText, zone)
        val end = parseActivityDateTime(newEndText, zone)
        if (start != null && end != null && end > start) {
            save(current.copy(startTimestamp = start, endTimestamp = end))
        }
    }

    val startEndError = run {
        val start = parseActivityDateTime(startText, zone)
        val end = parseActivityDateTime(endText, zone)
        when {
            start == null || end == null -> R.string.activity_datetime_invalid
            end <= start -> R.string.activity_end_before_start
            else -> null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackLink(onBack)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.activity_detail_title).uppercase(), style = HelionType.label, color = colors.textSecondary)
            Text(
                stringResource(statusLabelRes(current.status)).uppercase(),
                style = HelionType.label,
                color = if (needsAttention(current.status)) colors.accentAmber else colors.textTertiary,
            )
        }

        Text(stringResource(R.string.activity_title_label), style = HelionType.bodySmall, color = colors.textSecondary)
        OutlinedTextField(
            value = titleText,
            onValueChange = { text ->
                titleText = text
                save(current.copy(title = text.ifBlank { null }))
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(stringResource(R.string.sport_picker_label), style = HelionType.bodySmall, color = colors.textSecondary)
        SportPicker(selected = current.sport, onSelect = { save(current.copy(sport = it)) })

        Text(stringResource(R.string.activity_start_label), style = HelionType.bodySmall, color = colors.textSecondary)
        OutlinedTextField(
            value = startText,
            onValueChange = { text ->
                startText = text
                applyStartEnd(text, endText)
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(stringResource(R.string.activity_end_label), style = HelionType.bodySmall, color = colors.textSecondary)
        OutlinedTextField(
            value = endText,
            onValueChange = { text ->
                endText = text
                applyStartEnd(startText, text)
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        startEndError?.let { errorRes ->
            Text(stringResource(errorRes), style = HelionType.bodySmall, color = colors.accentAmber)
        }

        current.detectionContext?.takeIf { it.isNotBlank() }?.let { detectionContext ->
            Text(
                stringResource(R.string.activity_detection_context_label),
                style = HelionType.bodySmall,
                color = colors.textSecondary,
            )
            Text(detectionContext, style = HelionType.body, color = colors.textSecondary)
        }

        Text(stringResource(R.string.activity_notes_label), style = HelionType.bodySmall, color = colors.textSecondary)
        OutlinedTextField(
            value = notesText,
            onValueChange = { text ->
                notesText = text
                save(current.copy(notes = text.ifBlank { null }))
            },
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider(color = colors.divider)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (current.status) {
                ActivityStatus.CANDIDATE -> {
                    Button(onClick = { save(current.copy(status = ActivityStatus.CONFIRMED)) }) {
                        Text(stringResource(R.string.activity_action_confirm))
                    }
                    OutlinedButton(onClick = { save(current.copy(status = ActivityStatus.DISMISSED)) }) {
                        Text(stringResource(R.string.activity_action_dismiss))
                    }
                }

                ActivityStatus.DISMISSED -> {
                    Button(onClick = { save(current.copy(status = ActivityStatus.CANDIDATE)) }) {
                        Text(stringResource(R.string.activity_action_reopen))
                    }
                }

                ActivityStatus.CONFIRMED, ActivityStatus.PUBLISHED -> Unit
            }
        }

        HorizontalDivider(color = colors.divider)

        // Kept away from the send action at the very bottom of this screen (see below) so
        // scrolling down to send never lands a thumb on delete instead -- the confirmation
        // dialog is still there either way, but distance is the first line of defense.
        OutlinedButton(onClick = { showDeleteConfirm = true }) {
            Text(stringResource(R.string.activity_action_delete))
        }

        HorizontalDivider(color = colors.divider)

        Text(stringResource(R.string.calorie_section_title).uppercase(), style = HelionType.label, color = colors.textSecondary)
        when (val estimate = calorieEstimate) {
            null -> Unit // still loading -- nothing to say yet, rather than a flash of "no data"
            is ActivityCalorieEstimate.ProfileIncomplete ->
                Text(stringResource(R.string.calorie_needs_profile), style = HelionType.bodySmall, color = colors.textSecondary)
            is ActivityCalorieEstimate.NoHeartRateData ->
                Text(stringResource(R.string.calorie_no_heart_rate), style = HelionType.bodySmall, color = colors.textSecondary)
            is ActivityCalorieEstimate.Estimated -> {
                Text(stringResource(R.string.calorie_value, estimate.kcal), style = HelionType.body, color = colors.textPrimary)
                Text(stringResource(R.string.calorie_accuracy_note), style = HelionType.bodySmall, color = colors.textSecondary)
            }
        }

        HorizontalDivider(color = colors.divider)

        // The one send action on this screen, deliberately last: it goes through the
        // owner's own server (see CustomServerPublisher's own kdoc), which relays the
        // activity on to Strava -- the mechanism `custom_server_send_note` states plainly
        // rather than hiding behind the button alone.
        Text(stringResource(R.string.custom_server_section_title).uppercase(), style = HelionType.label, color = colors.textSecondary)
        Text(stringResource(R.string.custom_server_send_note), style = HelionType.bodySmall, color = colors.textSecondary)

        val currentCustomServerPublication = customServerPublication
        if (currentCustomServerPublication != null) {
            Text(
                stringResource(customServerStateLabelRes(currentCustomServerPublication.state)),
                style = HelionType.bodySmall,
                color = if (currentCustomServerPublication.state == PublicationState.FAILED) colors.accentAmber else colors.textSecondary,
            )
            if (currentCustomServerPublication.state == PublicationState.FAILED) {
                Text(
                    stringResource(
                        customServerFailureReasonRes(currentCustomServerPublication.lastError),
                        *customServerFailureReasonArgs(
                            currentCustomServerPublication.lastError,
                            currentCustomServerPublication.lastErrorDetail,
                        ).toTypedArray(),
                    ),
                    style = HelionType.bodySmall,
                    color = colors.accentAmber,
                )
            } else if (currentCustomServerPublication.lastMessage != null) {
                // The server's own text, verbatim (status included) -- see
                // CustomServerPublisher's own kdoc for why this replaces nothing when
                // there was no real message to show (an empty body, or one unreadable as
                // text): the state label above already stands on its own in that case.
                Text(
                    stringResource(R.string.custom_server_response_detail, currentCustomServerPublication.lastMessage),
                    style = HelionType.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }

        OutlinedButton(onClick = { sendToCustomServer() }, enabled = !sendingToCustomServer && current.sport != null) {
            Text(stringResource(R.string.custom_server_send_action))
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.activity_delete_confirm_title)) },
            text = { Text(stringResource(R.string.activity_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    scope.launch {
                        container.database.activities().delete(activityId)
                        onDeleted()
                    }
                }) {
                    Text(stringResource(R.string.activity_action_delete))
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
private fun BackLink(onBack: () -> Unit) {
    val colors = HelionThemeTokens.colors
    Text(
        stringResource(R.string.action_back),
        style = HelionType.label,
        color = colors.accentViolet,
        modifier = Modifier.clickable(onClick = onBack),
    )
}
