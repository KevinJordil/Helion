package ch.kevinjordil.helion.ui.activity

import ch.kevinjordil.helion.ui.theme.HelionBackLink
import androidx.compose.foundation.layout.offset
import ch.kevinjordil.helion.ui.theme.HelionTextButton
import ch.kevinjordil.helion.ui.theme.HelionOutlinedButton
import ch.kevinjordil.helion.ui.theme.HelionButton
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
import androidx.compose.material3.Text
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
import ch.kevinjordil.helion.activity.ActivityBadge
import ch.kevinjordil.helion.activity.ActivityBadgeReader
import ch.kevinjordil.helion.calorie.ActivityCalorieEstimate
import ch.kevinjordil.helion.calorie.estimateActivityCalories
import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.store.ActivityStatus
import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.store.Publication
import ch.kevinjordil.helion.store.PublicationState
import ch.kevinjordil.helion.store.PublicationTarget
import ch.kevinjordil.helion.ui.theme.HelionCardSpacing
import ch.kevinjordil.helion.ui.theme.HelionField
import ch.kevinjordil.helion.ui.theme.HelionFieldLabel
import ch.kevinjordil.helion.ui.theme.HelionScreenEdgeMargin
import ch.kevinjordil.helion.ui.theme.HelionStatItem
import ch.kevinjordil.helion.ui.theme.HelionSurface
import ch.kevinjordil.helion.ui.theme.HelionSurfacePadding
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import ch.kevinjordil.helion.ui.theme.HelionWarning
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val REVIEW_DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)
private val REVIEW_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)

/**
 * Why the publish button is disabled, if it is -- said on screen next to the button rather
 * than left as an unexplained grey button. Null when publishing is possible.
 */
fun publishBlockerRes(sportKnown: Boolean, serverConfigured: Boolean): Int? = when {
    !sportKnown -> R.string.activity_publish_needs_sport
    !serverConfigured -> R.string.activity_publish_needs_server
    else -> null
}

/**
 * One activity, laid out for the decision the owner comes here to make, top to bottom:
 *
 * 1. What happened: sport, day and time, duration, average and peak heart rate, calories, and
 *    the heart-rate curve -- enough to tell a real session from a false detection at a glance.
 * 2. The decision itself, one card: for a candidate, "Confirmer et publier sur Strava" as the
 *    main action (with the sport picker right there when no sport is known yet, since a
 *    sport is what publishing needs), "Confirmer sans publier" and "Ignorer"; for a confirmed
 *    or published activity, the Strava send and its last result; for a dismissed one,
 *    "Rouvrir". A disabled publish button always says why ([publishBlockerRes]).
 * 3. Records (badges), then every editable field, then delete -- last and away from the
 *    send button, behind its confirmation dialog.
 *
 * Every field saves itself the moment it holds a valid value -- there is no separate "Save"
 * step to forget -- so an edit is never silently lost by navigating away mid-thought. The
 * one field that can be genuinely invalid mid-edit, start/end text, shows its own inline note
 * instead of being saved or silently reverted while it is invalid: what is on screen is
 * always either what is stored, or an explicit "not yet applied" state.
 *
 * Publishing goes through the owner's own server, which relays the activity to Strava (see
 * [ch.kevinjordil.helion.customserver.CustomServerPublisher]); a send the server accepts
 * moves the activity to [ActivityStatus.PUBLISHED].
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
    var samples by remember(activityId) { mutableStateOf<List<MinuteSample>>(emptyList()) }
    var calorieEstimate by remember(activityId) { mutableStateOf<ActivityCalorieEstimate?>(null) }
    // Recomputed whenever this activity changes, not once on open: confirming a candidate
    // is exactly what makes it eligible for a record (see computeBadges), so the badges
    // must appear on the same screen that did the confirming.
    var badges by remember(activityId) { mutableStateOf<List<ActivityBadge>>(emptyList()) }
    val serverConfigured = remember { container.customServerConfig.isConfigured }

    suspend fun reloadCustomServerPublication() {
        customServerPublication = container.database.publications().get(activityId, PublicationTarget.CUSTOM_SERVER)
    }

    LaunchedEffect(activityId) {
        val loaded = container.database.activities().get(activityId)
        activity = loaded
        reloadCustomServerPublication()
        loadedOnce = true
    }

    // Keyed on the time range, not just the id: editing start or end changes which minutes
    // belong to the activity, and the curve and figures must follow.
    val range = activity?.let { it.startTimestamp to it.endTimestamp }
    LaunchedEffect(range) {
        val (start, end) = range ?: return@LaunchedEffect
        val loadedSamples = withContext(Dispatchers.IO) { container.database.minuteSamples().between(start, end) }
        samples = loadedSamples
        calorieEstimate = estimateActivityCalories(container.profile, start, zone, loadedSamples)
    }

    // Keyed on the activity itself rather than on its id: a record depends on this
    // activity's status, sport and duration, all of which this screen can change, and a
    // badge that only appeared on the next visit would look like a bug.
    val badgeReader = remember(container) { ActivityBadgeReader(container.database) }
    LaunchedEffect(activity) {
        val current = activity
        badges = if (current == null) emptyList() else badgeReader.loadBadges()[current.id].orEmpty()
    }

    fun save(updated: Activity) {
        // Any owner-initiated change -- confirming, dismissing, or hand-editing a field --
        // is exactly as final a decision as a genuinely observed boundary: clear
        // Activity.provisional here so a later detection pass never grows this row again,
        // even over a field this screen never touches (title, notes, sport).
        val settled = updated.copy(provisional = false)
        val statusChanged = settled.status != activity?.status
        activity = settled
        scope.launch { container.database.activities().update(settled) }
        // Decided here, so its notification (if still showing) has nothing left to ask.
        if (statusChanged) container.candidateNotifier.cancelCandidate(activityId)
    }

    /** Sends to Strava; [confirmFirst] also confirms a candidate in the same tap. */
    fun publish(current: Activity, confirmFirst: Boolean) {
        if (sendingToCustomServer) return
        sendingToCustomServer = true
        scope.launch {
            if (confirmFirst && current.status == ActivityStatus.CANDIDATE) {
                val confirmed = current.copy(status = ActivityStatus.CONFIRMED, provisional = false)
                activity = confirmed
                container.database.activities().update(confirmed)
                container.candidateNotifier.cancelCandidate(activityId)
            }
            withContext(Dispatchers.IO) { container.customServerPublisher.send(activityId) }
            // A successful send moved the activity to PUBLISHED in the database.
            activity = container.database.activities().get(activityId)
            reloadCustomServerPublication()
            sendingToCustomServer = false
        }
    }

    val current = activity

    if (current == null) {
        Column(modifier = modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(HelionCardSpacing)) {
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

    // The picker sits in the decision card while a candidate still has no sport (publishing
    // needs one), and in the details otherwise -- never in both places at once.
    val pickerInDecision = current.status == ActivityStatus.CANDIDATE && current.sport == null
    val publishBlocker = publishBlockerRes(sportKnown = current.sport != null, serverConfigured = serverConfigured)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = HelionScreenEdgeMargin, vertical = HelionCardSpacing),
        verticalArrangement = Arrangement.spacedBy(HelionCardSpacing),
    ) {
        BackLink(onBack, modifier = Modifier.padding(horizontal = HelionSurfacePadding))

        // 1. What happened.
        HelionSurface(
            modifier = Modifier.fillMaxWidth(),
            padding = PaddingValues(HelionSurfacePadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    current.sport?.let { stringResource(sportLabelRes(it)) } ?: stringResource(R.string.activity_review_sport_missing),
                    style = HelionType.title,
                    color = if (current.sport == null) colors.attention else colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(statusLabelRes(current.status)),
                    style = HelionType.label,
                    color = if (needsAttention(current.status)) colors.attention else colors.textTertiary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            val startAt = Instant.ofEpochSecond(current.startTimestamp).atZone(zone)
            val endAt = Instant.ofEpochSecond(current.endTimestamp).atZone(zone)
            Text(
                "${REVIEW_DAY_FORMAT.format(startAt).replaceFirstChar { it.titlecase(Locale.FRENCH) }} · " +
                    "${REVIEW_TIME_FORMAT.format(startAt)}–${REVIEW_TIME_FORMAT.format(endAt)}",
                style = HelionType.bodySmall,
                color = colors.textSecondary,
            )

            val heartRates = samples.mapNotNull { it.heartRate }
            Row(modifier = Modifier.fillMaxWidth()) {
                HelionStatItem(
                    label = stringResource(R.string.activity_stat_duration),
                    value = activityDurationText(current.endTimestamp - current.startTimestamp),
                    modifier = Modifier.weight(1f),
                    centred = true,
                )
                if (heartRates.isNotEmpty()) {
                    HelionStatItem(
                        label = stringResource(R.string.activity_stat_heart_rate_average),
                        value = heartRates.average().roundToInt().toString(),
                        unit = stringResource(R.string.unit_bpm),
                        modifier = Modifier.weight(1f),
                        centred = true,
                    )
                    HelionStatItem(
                        label = stringResource(R.string.activity_stat_heart_rate_max),
                        value = heartRates.max().toString(),
                        unit = stringResource(R.string.unit_bpm),
                        modifier = Modifier.weight(1f),
                        centred = true,
                    )
                }
                (calorieEstimate as? ActivityCalorieEstimate.Estimated)?.let { estimate ->
                    HelionStatItem(
                        label = stringResource(R.string.activity_stat_calories),
                        value = stringResource(R.string.activity_stat_calories_value, estimate.kcal),
                        unit = stringResource(R.string.unit_kcal),
                        modifier = Modifier.weight(1f),
                        centred = true,
                    )
                }
            }

            HeartRateChart(
                samples = samples,
                start = current.startTimestamp,
                end = current.endTimestamp,
                color = colors.metricHues.first(),
                description = stringResource(R.string.activity_review_heart_rate_title),
                modifier = Modifier.padding(top = 4.dp),
            )

            when (calorieEstimate) {
                is ActivityCalorieEstimate.ProfileIncomplete ->
                    Text(stringResource(R.string.calorie_needs_profile), style = HelionType.bodySmall, color = colors.textTertiary)
                is ActivityCalorieEstimate.NoHeartRateData ->
                    Text(stringResource(R.string.calorie_no_heart_rate), style = HelionType.bodySmall, color = colors.textTertiary)
                is ActivityCalorieEstimate.Estimated ->
                    Text(stringResource(R.string.calorie_accuracy_note), style = HelionType.bodySmall, color = colors.textTertiary)
                null -> Unit
            }

            current.detectionContext?.takeIf { it.isNotBlank() && current.status == ActivityStatus.CANDIDATE }?.let { context ->
                Text(context, style = HelionType.bodySmall, color = colors.textTertiary)
            }
        }

        // 2. The decision.
        HelionSurface(
            modifier = Modifier.fillMaxWidth(),
            padding = PaddingValues(HelionSurfacePadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(
                    if (current.status == ActivityStatus.CANDIDATE) R.string.activity_review_decision_title else R.string.custom_server_section_title,
                ),
                style = HelionType.title,
                color = colors.textPrimary,
            )

            if (pickerInDecision) {
                HelionFieldLabel(stringResource(R.string.activity_review_sport_question))
                SportPicker(
                    selected = current.sport,
                    onSelect = { save(current.copy(sport = it)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            when (current.status) {
                ActivityStatus.CANDIDATE -> {
                    HelionButton(
                        onClick = { publish(current, confirmFirst = true) },
                        enabled = publishBlocker == null && !sendingToCustomServer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.activity_action_confirm_publish))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HelionOutlinedButton(
                            onClick = { save(current.copy(status = ActivityStatus.CONFIRMED)) },
                            enabled = !sendingToCustomServer,
                        ) {
                            Text(stringResource(R.string.activity_action_confirm_only))
                        }
                        HelionOutlinedButton(
                            onClick = { save(current.copy(status = ActivityStatus.DISMISSED)) },
                            enabled = !sendingToCustomServer,
                        ) {
                            Text(stringResource(R.string.activity_action_dismiss))
                        }
                    }
                }

                ActivityStatus.CONFIRMED, ActivityStatus.PUBLISHED -> {
                    if (current.status == ActivityStatus.CONFIRMED && customServerPublication == null) {
                        Text(stringResource(R.string.activity_publish_not_yet), style = HelionType.bodySmall, color = colors.textSecondary)
                    }
                    HelionButton(
                        onClick = { publish(current, confirmFirst = false) },
                        enabled = publishBlocker == null && !sendingToCustomServer,
                    ) {
                        Text(
                            stringResource(
                                if (current.status == ActivityStatus.PUBLISHED) R.string.activity_action_republish else R.string.activity_action_publish,
                            ),
                        )
                    }
                }

                ActivityStatus.DISMISSED -> {
                    HelionButton(onClick = { save(current.copy(status = ActivityStatus.CANDIDATE)) }) {
                        Text(stringResource(R.string.activity_action_reopen))
                    }
                }
            }

            if (current.status != ActivityStatus.DISMISSED) {
                when {
                    sendingToCustomServer ->
                        Text(stringResource(R.string.activity_publish_sending), style = HelionType.bodySmall, color = colors.textSecondary)
                    publishBlocker != null ->
                        Text(stringResource(publishBlocker), style = HelionType.bodySmall, color = colors.textSecondary)
                    else ->
                        Text(stringResource(R.string.custom_server_send_note), style = HelionType.bodySmall, color = colors.textTertiary)
                }
                customServerPublication?.let { publication -> PublicationStatus(publication) }
            }
        }

        // 3. Records, then the details.
        // Absent entirely when there is nothing to show -- an empty "Distinctions" card
        // would tell him he has failed at something.
        if (badges.isNotEmpty()) {
            HelionSurface(
                modifier = Modifier.fillMaxWidth(),
                padding = PaddingValues(HelionSurfacePadding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HelionFieldLabel(stringResource(R.string.badge_section_title))
                BadgeChips(badges)
            }
        }

        Text(
            stringResource(R.string.activity_review_details_title),
            style = HelionType.title,
            color = colors.textPrimary,
            modifier = Modifier.padding(horizontal = HelionSurfacePadding),
        )

        HelionSurface(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(HelionSurfacePadding)) {
            HelionField(
                label = stringResource(R.string.activity_title_label),
                value = titleText,
                onValueChange = { text ->
                    titleText = text
                    save(current.copy(title = text.ifBlank { null }))
                },
            )
        }

        if (!pickerInDecision) {
            HelionSurface(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(HelionSurfacePadding)) {
                HelionFieldLabel(stringResource(R.string.sport_picker_label))
                SportPicker(
                    selected = current.sport,
                    onSelect = { save(current.copy(sport = it)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }

        // Start and end: the two ends of the same activity, exactly the pairing Sommeil's
        // own bedtime/wake row uses, so they share one card rather than each getting its own.
        HelionSurface(
            modifier = Modifier.fillMaxWidth(),
            padding = PaddingValues(HelionSurfacePadding),
            verticalArrangement = Arrangement.spacedBy(HelionCardSpacing),
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

        // Shown in the summary while the activity is still a candidate (that is when it
        // helps decide); kept here afterwards for reference.
        current.detectionContext?.takeIf { it.isNotBlank() && current.status != ActivityStatus.CANDIDATE }?.let { detectionContext ->
            HelionSurface(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(HelionSurfacePadding)) {
                HelionFieldLabel(stringResource(R.string.activity_detection_context_label))
                Text(
                    detectionContext,
                    style = HelionType.body,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        HelionSurface(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(HelionSurfacePadding)) {
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

        // Last on the screen and far from the send button, so scrolling to send never lands
        // a thumb on delete -- the confirmation dialog is still there either way.
        HelionOutlinedButton(
            onClick = { showDeleteConfirm = true },
            modifier = Modifier.padding(horizontal = HelionSurfacePadding),
        ) {
            Text(stringResource(R.string.activity_action_delete))
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.activity_delete_confirm_title)) },
            text = { Text(stringResource(R.string.activity_delete_confirm_body)) },
            confirmButton = {
                HelionTextButton(onClick = {
                    showDeleteConfirm = false
                    scope.launch {
                        container.database.activities().delete(activityId)
                        container.candidateNotifier.cancelCandidate(activityId)
                        onDeleted()
                    }
                }) {
                    Text(stringResource(R.string.activity_action_delete))
                }
            },
            dismissButton = {
                HelionTextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** The last send's outcome: its state, and the server's own words or the failure reason. */
@Composable
private fun PublicationStatus(publication: Publication) {
    val colors = HelionThemeTokens.colors
    if (publication.state == PublicationState.FAILED) {
        HelionWarning(stringResource(customServerStateLabelRes(publication.state)))
        HelionWarning(
            stringResource(
                customServerFailureReasonRes(publication.lastError),
                *customServerFailureReasonArgs(publication.lastError, publication.lastErrorDetail).toTypedArray(),
            ),
        )
    } else {
        Text(stringResource(customServerStateLabelRes(publication.state)), style = HelionType.bodySmall, color = colors.textSecondary)
        // The server's own text, verbatim (status included) -- see CustomServerPublisher's own
        // kdoc for why nothing is shown when there was no real message.
        publication.lastMessage?.let { message ->
            Text(stringResource(R.string.custom_server_response_detail, message), style = HelionType.bodySmall, color = colors.textSecondary)
        }
    }
}

@Composable
private fun BackLink(onBack: () -> Unit, modifier: Modifier = Modifier) {
    HelionBackLink(onBack, modifier.padding(start = 0.dp).offset(x = (-8).dp))
}
