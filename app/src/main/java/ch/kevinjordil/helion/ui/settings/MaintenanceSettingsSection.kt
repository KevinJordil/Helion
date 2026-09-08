package ch.kevinjordil.helion.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
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
import ch.kevinjordil.helion.activity.ReanalysisOutcome
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val LAST_REANALYSIS_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())

private val LAST_BACKGROUND_SYNC_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())

/**
 * Re-runs activity detection over the entire archive Helion already holds, for whatever
 * candidates a slot or a threshold change since the last full pass would now catch --
 * Réglages' [SettingsSection.MAINTENANCE] entry. Moved here unchanged from Activités, then
 * again from one long Réglages scroll into its own sub-screen: same action, same progress
 * state, same result message, only the surrounding screen changed.
 *
 * Cancelling here means abandoning [ArchiveReanalyzer.reanalyze] between two of its own
 * bounded slices (see its kdoc) -- always safe, since every slice it already finished
 * committed its own overlap-checked inserts, and a later re-run (this action tapped again)
 * simply resumes covering the rest of the archive without duplicating anything that slice
 * already produced.
 *
 * Also shows when the *background* worker itself last ran (see
 * [ch.kevinjordil.helion.store.SyncState.lastBackgroundSyncAttempt]'s kdoc for why that is
 * a different fact from "a sync ran recently" -- opening the app runs one every time) and,
 * since the one thing this app cannot do anything about from inside itself is a phone that
 * refuses to schedule it, a direct way to reach the per-app battery setting that -- on this
 * owner's Samsung phone in particular -- is what silently starves it: "Mise en veille" or
 * "Mise en veille profonde" put an infrequently-opened app's background work to sleep by
 * default. [Settings.ACTION_APPLICATION_DETAILS_SETTINGS] is the one battery-related screen
 * every Android build exposes through a stable, documented action; the OEM-specific
 * "sleeping apps" list Samsung actually uses has no public entry point at all, so pointing at
 * the app's own settings page -- one tap further to the battery section from there -- is the
 * closest this code can get the owner without guessing at a vendor-specific Intent that the
 * next One UI release could rename or remove outright.
 *
 * Moved here from [SourceSettingsSection] -- this is diagnostic status about why background
 * work isn't happening, which the owner reasonably expects to find alongside the other
 * diagnostic action this screen already had (the re-analysis button above), not under "where
 * does my data come from".
 */
@Composable
fun ArchiveReanalysisSection(container: AppContainer) {
    val context = LocalContext.current
    val colors = HelionThemeTokens.colors
    val scope = rememberCoroutineScope()

    var lastFullReanalysis by remember { mutableStateOf<Long?>(null) }
    var reanalysisJob by remember { mutableStateOf<Job?>(null) }
    var reanalysisMessageRes by remember { mutableStateOf<Int?>(null) }
    var reanalysisMessageArgs by remember { mutableStateOf(emptyList<Any>()) }
    var lastBackgroundSync by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        lastFullReanalysis = container.database.syncState().get()?.lastFullDetectionRun
        lastBackgroundSync = container.database.syncState().get()?.lastBackgroundSyncAttempt
    }

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

    Text(
        lastBackgroundSync?.let {
            stringResource(R.string.source_last_background_sync, LAST_BACKGROUND_SYNC_FORMAT.format(Instant.ofEpochSecond(it)))
        } ?: stringResource(R.string.source_last_background_sync_never),
        style = HelionType.bodySmall,
        color = colors.textTertiary,
    )
    Text(stringResource(R.string.source_background_sync_battery_hint), style = HelionType.bodySmall, color = colors.textSecondary)
    Button(
        onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                },
            )
        },
    ) {
        Text(stringResource(R.string.source_open_battery_settings))
    }
}
