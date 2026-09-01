package ch.kevinjordil.helion.ui.settings

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
 */
@Composable
fun ArchiveReanalysisSection(container: AppContainer) {
    val colors = HelionThemeTokens.colors
    val scope = rememberCoroutineScope()

    var lastFullReanalysis by remember { mutableStateOf<Long?>(null) }
    var reanalysisJob by remember { mutableStateOf<Job?>(null) }
    var reanalysisMessageRes by remember { mutableStateOf<Int?>(null) }
    var reanalysisMessageArgs by remember { mutableStateOf(emptyList<Any>()) }

    LaunchedEffect(Unit) {
        lastFullReanalysis = container.database.syncState().get()?.lastFullDetectionRun
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
}
