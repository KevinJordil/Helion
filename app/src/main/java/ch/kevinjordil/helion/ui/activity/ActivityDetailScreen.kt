package ch.kevinjordil.helion.ui.activity

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.calorie.ActivityCalorieEstimate
import ch.kevinjordil.helion.calorie.estimateActivityCalories
import ch.kevinjordil.helion.export.DownloadsSaveResult
import ch.kevinjordil.helion.export.buildOpenStravaIntent
import ch.kevinjordil.helion.export.buildShareIntent
import ch.kevinjordil.helion.export.saveTcxToDownloads
import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.store.ActivityStatus
import ch.kevinjordil.helion.store.MinuteSample
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
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }

    var activity by remember(activityId) { mutableStateOf<Activity?>(null) }
    var loadedOnce by remember(activityId) { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var customServerPublication by remember(activityId) { mutableStateOf<Publication?>(null) }
    var sendingToCustomServer by remember(activityId) { mutableStateOf(false) }
    var minuteSamples by remember(activityId) { mutableStateOf<List<MinuteSample>>(emptyList()) }
    var calorieEstimate by remember(activityId) { mutableStateOf<ActivityCalorieEstimate?>(null) }

    suspend fun reloadCustomServerPublication() {
        customServerPublication = container.database.publications().get(activityId, PublicationTarget.CUSTOM_SERVER)
    }

    LaunchedEffect(activityId) {
        val loaded = container.database.activities().get(activityId)
        activity = loaded
        reloadCustomServerPublication()
        // Loaded alongside the activity itself, not lazily inside shareTcx: the calorie
        // estimate needs the same per-minute samples that action sends along, and computing
        // it once here means the detail screen and the share action agree on the exact same
        // figure.
        if (loaded != null) {
            val samples = withContext(Dispatchers.IO) {
                container.database.minuteSamples().between(loaded.startTimestamp, loaded.endTimestamp)
            }
            minuteSamples = samples
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

    fun shareTcx(current: Activity) {
        val estimated = (calorieEstimate as? ActivityCalorieEstimate.Estimated)?.kcal
        context.startActivity(buildShareIntent(context, current, minuteSamples, estimated))
    }

    // Holds the activity a save was requested for while a permission request is in
    // flight (API 26-28 only, see [saveTcxToDownloads]'s own kdoc) -- read back once
    // granted to retry the exact same save, rather than the owner having to tap
    // "Enregistrer le fichier" a second time.
    var pendingSave by remember(activityId) { mutableStateOf<Activity?>(null) }

    // A closure captured by reference: [storagePermissionLauncher]'s callback below needs
    // to call [saveTcx] once permission is granted, but [saveTcx] itself needs
    // [storagePermissionLauncher] to request that permission in the first place -- this
    // mutable indirection is what lets the two refer to each other despite the launcher
    // having to be created first (`rememberLauncherForActivityResult` cannot itself be
    // called lazily inside a plain function).
    var retrySave: (Activity) -> Unit = {}

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val target = pendingSave
        pendingSave = null
        if (granted && target != null) {
            retrySave(target)
        } else if (!granted) {
            Toast.makeText(context, R.string.strava_storage_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    fun saveTcx(target: Activity) {
        val estimated = (calorieEstimate as? ActivityCalorieEstimate.Estimated)?.kcal
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                saveTcxToDownloads(context, target, minuteSamples, estimated)
            }
            when (result) {
                is DownloadsSaveResult.Saved -> {
                    Toast.makeText(
                        context,
                        context.getString(R.string.strava_save_confirmation, result.fileName),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                is DownloadsSaveResult.Failed -> {
                    Toast.makeText(
                        context,
                        context.getString(R.string.strava_save_failed, result.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                DownloadsSaveResult.PermissionRequired -> {
                    // Only reachable on API 26-28 -- see [saveTcxToDownloads]'s own kdoc.
                    pendingSave = target
                    Toast.makeText(context, R.string.strava_storage_permission_rationale, Toast.LENGTH_LONG).show()
                    storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }
    }
    retrySave = ::saveTcx

    fun openStrava() {
        context.startActivity(buildOpenStravaIntent())
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

        // The manual flow -- save the file, open Strava, import it -- is the owner's real
        // route (see the module's own brief): reachable right under the header, before any
        // editable field, the status row or the calorie estimate.
        Text(stringResource(R.string.strava_section_title).uppercase(), style = HelionType.label, color = colors.textSecondary)

        // Stated once, here, before the two actions below -- never repeated on every
        // failed publish or every share -- because it is a TCX format limitation the owner
        // has already hit, not something that changes per attempt.
        Text(stringResource(R.string.strava_sport_fix_note), style = HelionType.bodySmall, color = colors.textSecondary)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { saveTcx(current) }) {
                Text(stringResource(R.string.strava_save_action))
            }
            Button(onClick = { openStrava() }) {
                Text(stringResource(R.string.strava_open_action))
            }
        }
        Text(stringResource(R.string.strava_import_steps), style = HelionType.bodySmall, color = colors.textSecondary)

        // The plain share action -- the same insurance the manual flow above already
        // relies on, just handed to another app's share target instead of Downloads.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { shareTcx(current) }) {
                Text(stringResource(R.string.strava_share_action))
            }
        }

        HorizontalDivider(color = colors.divider)

        // A second, independent send target: the owner's own server. Kept as its own
        // section with its own state -- it tracks a separate PublicationTarget row (see
        // CustomServerPublisher's own kdoc), and it can fail or succeed on a completely
        // different schedule than the manual-flow save above.
        Text(stringResource(R.string.custom_server_section_title).uppercase(), style = HelionType.label, color = colors.textSecondary)

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
            }
        }

        OutlinedButton(onClick = { sendToCustomServer() }, enabled = !sendingToCustomServer) {
            Text(stringResource(R.string.custom_server_send_action))
        }

        HorizontalDivider(color = colors.divider)

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

        OutlinedButton(onClick = { showDeleteConfirm = true }) {
            Text(stringResource(R.string.activity_action_delete))
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
