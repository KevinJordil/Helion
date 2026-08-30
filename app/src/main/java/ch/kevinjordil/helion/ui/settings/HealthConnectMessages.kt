package ch.kevinjordil.helion.ui.settings

import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.healthconnect.HealthConnectExportOutcome
import ch.kevinjordil.helion.store.HealthConnectExportState

/**
 * `stringResource(resId, *args)`-ready pair for one [HealthConnectExportOutcome] -- the same
 * shape [ch.kevinjordil.helion.ui.settings.syncMessage] already uses for a plain "Sync now"
 * result, kept free of Context and Compose so the mapping itself is a plain, testable
 * function.
 */
fun healthConnectOutcomeMessage(outcome: HealthConnectExportOutcome): Pair<Int, List<Any>> = when (outcome) {
    HealthConnectExportOutcome.Disabled -> R.string.health_connect_result_disabled to emptyList()
    HealthConnectExportOutcome.Unavailable -> R.string.health_connect_result_unavailable to emptyList()
    HealthConnectExportOutcome.PermissionMissing -> R.string.health_connect_result_permission_missing to emptyList()
    is HealthConnectExportOutcome.Completed -> healthConnectSummaryMessage(
        sleepSessions = outcome.summary.sleepSessions,
        exerciseSessions = outcome.summary.exerciseSessions,
        heartRateRecords = outcome.summary.heartRateRecords,
        stepsRecords = outcome.summary.stepsRecords,
        hrvRecords = outcome.summary.hrvRecords,
        spo2Records = outcome.summary.spo2Records,
        temperatureRecords = outcome.summary.temperatureRecords,
        respiratoryRateRecords = outcome.summary.respiratoryRateRecords,
    )
    is HealthConnectExportOutcome.Failed -> R.string.health_connect_result_failed to listOf(outcome.reason)
}

private fun healthConnectSummaryMessage(
    sleepSessions: Int,
    exerciseSessions: Int,
    heartRateRecords: Int,
    stepsRecords: Int,
    hrvRecords: Int,
    spo2Records: Int,
    temperatureRecords: Int,
    respiratoryRateRecords: Int,
): Pair<Int, List<Any>> = R.string.health_connect_result_success to listOf(
    sleepSessions, exerciseSessions, heartRateRecords, stepsRecords,
    hrvRecords, spo2Records, temperatureRecords, respiratoryRateRecords,
)

/**
 * The same message [healthConnectOutcomeMessage] would show for a fresh outcome, rebuilt
 * from [HealthConnectExportState] instead -- what Réglages shows for the *last* pass even
 * before "Exporter maintenant" is tapped again this session. Null when no pass has ever run
 * ([HealthConnectExportState.lastRunAttempt] is null, including when [state] itself is null
 * -- no row yet, same thing).
 */
fun healthConnectStateMessage(state: HealthConnectExportState?): Pair<Int, List<Any>>? {
    if (state?.lastRunAttempt == null) return null
    return if (state.lastError != null) {
        R.string.health_connect_result_failed to listOf(state.lastError)
    } else {
        healthConnectSummaryMessage(
            sleepSessions = state.sleepSessionsWritten,
            exerciseSessions = state.exerciseSessionsWritten,
            heartRateRecords = state.heartRateRecordsWritten,
            stepsRecords = state.stepsRecordsWritten,
            hrvRecords = state.hrvRecordsWritten,
            spo2Records = state.spo2RecordsWritten,
            temperatureRecords = state.temperatureRecordsWritten,
            respiratoryRateRecords = state.respiratoryRateRecordsWritten,
        )
    }
}
