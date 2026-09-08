package ch.kevinjordil.helion.ui.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import ch.kevinjordil.helion.ui.theme.HelionField
import ch.kevinjordil.helion.ui.theme.HelionFieldLabel
import ch.kevinjordil.helion.ui.theme.HelionSurface
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import ch.kevinjordil.helion.ui.theme.HelionWarning
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The root Column's own horizontal inset, matching the pattern every other screen's own
 * width tests are built on (Sommeil's `SCREEN_EDGE_MARGIN`, the metric detail's own):
 * [SCREEN_EDGE_MARGIN] plus [CARD_PADDING] is the historical 20dp inset
 * `ActivityLabelWidthTest` and `NoTextClippingTest` measure this screen's content against, so
 * moving each field onto its own card does not change either budget.
 */
private val SCREEN_EDGE_MARGIN = 4.dp

/** A card's own internal padding: [SCREEN_EDGE_MARGIN] plus this is the historical 20dp inset. */
private val CARD_PADDING = 16.dp

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
 *
 * Laid out the same way every other screen now is: one raised [HelionSurface] per field or
 * per genuine whole -- start and end share one card (the two ends of the same activity,
 * exactly the pairing Sommeil's bedtime/wake row uses), everything else (title, sport,
 * detection context, notes, calories, the Strava send) gets its own -- rather than the
 * plain, divider-separated column this screen used to be.
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
        // Any owner-initiated change -- confirming, dismissing, or hand-editing a field --
        // is exactly as final a decision as a genuinely observed boundary: clear
        // Activity.provisional here so a later detection pass never grows this row again,
        // even over a field this screen never touches (title, notes, sport).
        val settled = updated.copy(provisional = false)
        activity = settled
        scope.launch { container.database.activities().update(settled) }
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

    val startParsed = parseActivityDateTime(startText, zone)
    val endParsed = parseActivityDateTime(endText, zone)
    // Each field is marked invalid on its own -- a malformed start must not make the end
    // field look wrong too -- and the ordering problem (both parse, but end is not after
    // start) is called out on both, since neither one alone is "the" mistake.
    val startEndOrderInvalid = startParsed != null && endParsed != null && endParsed <= startParsed
    val startWarning = when {
        startParsed == null -> stringResource(R.string.activity_datetime_invalid)
        startEndOrderInvalid -> stringResource(R.string.activity_end_before_start)
        else -> null
    }
    val endWarning = when {
        endParsed == null -> stringResource(R.string.activity_datetime_invalid)
        startEndOrderInvalid -> stringResource(R.string.activity_end_before_start)
        else -> null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SCREEN_EDGE_MARGIN, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackLink(onBack, modifier = Modifier.padding(horizontal = CARD_PADDING))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = CARD_PADDING),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.activity_detail_title), style = HelionType.headline, color = colors.textPrimary)
            Text(
                stringResource(statusLabelRes(current.status)),
                style = HelionType.label,
                color = if (needsAttention(current.status)) colors.accentAmber else colors.textTertiary,
            )
        }

        HelionSurface(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(CARD_PADDING)) {
            HelionField(
                label = stringResource(R.string.activity_title_label),
                value = titleText,
                onValueChange = { text ->
                    titleText = text
                    save(current.copy(title = text.ifBlank { null }))
                },
            )
        }

        HelionSurface(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(CARD_PADDING)) {
            HelionFieldLabel(stringResource(R.string.sport_picker_label))
            SportPicker(
                selected = current.sport,
                onSelect = { save(current.copy(sport = it)) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }

        // Start and end: the two ends of the same activity, exactly the pairing Sommeil's
        // own bedtime/wake row uses, so they share one card rather than each getting its own.
        HelionSurface(
            modifier = Modifier.fillMaxWidth(),
            padding = PaddingValues(CARD_PADDING),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HelionField(
                label = stringResource(R.string.activity_start_label),
                value = startText,
                onValueChange = { text ->
                    startText = text
                    applyStartEnd(text, endText)
                },
                placeholder = stringResource(R.string.activity_datetime_placeholder),
                warning = startWarning,
            )
            HelionField(
                label = stringResource(R.string.activity_end_label),
                value = endText,
                onValueChange = { text ->
                    endText = text
                    applyStartEnd(startText, text)
                },
                placeholder = stringResource(R.string.activity_datetime_placeholder),
                warning = endWarning,
            )
        }

        current.detectionContext?.takeIf { it.isNotBlank() }?.let { detectionContext ->
            HelionSurface(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(CARD_PADDING)) {
                HelionFieldLabel(stringResource(R.string.activity_detection_context_label))
                Text(
                    detectionContext,
                    style = HelionType.body,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        HelionSurface(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(CARD_PADDING)) {
            HelionField(
                label = stringResource(R.string.activity_notes_label),
                value = notesText,
                onValueChange = { text ->
                    notesText = text
                    save(current.copy(notes = text.ifBlank { null }))
                },
                singleLine = false,
            )
        }

        // Status transitions and delete are actions, not measures, so -- like every other
        // action button in the app (Réglages' own Button calls, the empty-state action) --
        // they stay plain buttons on the page rather than riding on a card of their own.
        Row(modifier = Modifier.padding(horizontal = CARD_PADDING), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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

        // Kept away from the send action further down this screen (see below) so scrolling
        // down to send never lands a thumb on delete instead -- the confirmation dialog is
        // still there either way, but distance is the first line of defense.
        OutlinedButton(
            onClick = { showDeleteConfirm = true },
            modifier = Modifier.padding(horizontal = CARD_PADDING),
        ) {
            Text(stringResource(R.string.activity_action_delete))
        }

        HelionSurface(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(CARD_PADDING)) {
            Text(stringResource(R.string.calorie_section_title), style = HelionType.title, color = colors.textPrimary)
            when (val estimate = calorieEstimate) {
                null -> Unit // still loading -- nothing to say yet, rather than a flash of "no data"
                is ActivityCalorieEstimate.ProfileIncomplete ->
                    Text(
                        stringResource(R.string.calorie_needs_profile),
                        style = HelionType.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                is ActivityCalorieEstimate.NoHeartRateData ->
                    Text(
                        stringResource(R.string.calorie_no_heart_rate),
                        style = HelionType.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                is ActivityCalorieEstimate.Estimated -> {
                    Text(
                        stringResource(R.string.calorie_value, estimate.kcal),
                        style = HelionType.body,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(stringResource(R.string.calorie_accuracy_note), style = HelionType.bodySmall, color = colors.textSecondary)
                }
            }
        }

        // The one send action on this screen, deliberately last: it goes through the
        // owner's own server (see CustomServerPublisher's own kdoc), which relays the
        // activity on to Strava -- the mechanism `custom_server_send_note` states plainly
        // rather than hiding behind the button alone.
        HelionSurface(
            modifier = Modifier.fillMaxWidth(),
            padding = PaddingValues(CARD_PADDING),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.custom_server_section_title), style = HelionType.title, color = colors.textPrimary)
            Text(stringResource(R.string.custom_server_send_note), style = HelionType.bodySmall, color = colors.textSecondary)

            val currentCustomServerPublication = customServerPublication
            if (currentCustomServerPublication != null) {
                if (currentCustomServerPublication.state == PublicationState.FAILED) {
                    HelionWarning(
                        stringResource(customServerStateLabelRes(currentCustomServerPublication.state)),
                    )
                    HelionWarning(
                        stringResource(
                            customServerFailureReasonRes(currentCustomServerPublication.lastError),
                            *customServerFailureReasonArgs(
                                currentCustomServerPublication.lastError,
                                currentCustomServerPublication.lastErrorDetail,
                            ).toTypedArray(),
                        ),
                    )
                } else {
                    Text(
                        stringResource(customServerStateLabelRes(currentCustomServerPublication.state)),
                        style = HelionType.bodySmall,
                        color = colors.textSecondary,
                    )
                    if (currentCustomServerPublication.lastMessage != null) {
                        // The server's own text, verbatim (status included) -- see
                        // CustomServerPublisher's own kdoc for why this replaces nothing
                        // when there was no real message to show (an empty body, or one
                        // unreadable as text): the state label above already stands on its
                        // own in that case.
                        Text(
                            stringResource(R.string.custom_server_response_detail, currentCustomServerPublication.lastMessage),
                            style = HelionType.bodySmall,
                            color = colors.textSecondary,
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = { sendToCustomServer() },
                enabled = !sendingToCustomServer && current.sport != null,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(stringResource(R.string.custom_server_send_action))
            }
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
private fun BackLink(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    Text(
        stringResource(R.string.action_back),
        style = HelionType.label,
        color = colors.accentViolet,
        modifier = modifier.clickable(onClick = onBack),
    )
}
