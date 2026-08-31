package ch.kevinjordil.helion.healthconnect

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import ch.kevinjordil.helion.export.externalIdFor
import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.store.HealthConnectExportState
import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.store.PointSample
import ch.kevinjordil.helion.store.Publication
import ch.kevinjordil.helion.store.PublicationState
import ch.kevinjordil.helion.store.PublicationTarget
import ch.kevinjordil.helion.ui.settings.HealthConnectConfig
import kotlinx.coroutines.CancellationException

/** How one export pass went -- what Réglages' Health Connect section reads to say what happened. */
sealed interface HealthConnectExportOutcome {
    /** [HealthConnectConfig.enabled] is false. Nothing was read, nothing was written. */
    data object Disabled : HealthConnectExportOutcome

    /** Health Connect is not installed, or too old for the record types this app writes. See [healthConnectAvailability]. */
    data object Unavailable : HealthConnectExportOutcome

    /** The feature is on and Health Connect is present, but the owner has not (or no longer) granted every write permission this app asks for. */
    data object PermissionMissing : HealthConnectExportOutcome

    data class Completed(val summary: HealthConnectExportSummary) : HealthConnectExportOutcome

    /**
     * [partialSummary] is what actually made it to Health Connect before [reason] cut the
     * pass short -- see [HealthConnectExporter]'s own kdoc on batching for why a failure
     * partway through is never all-or-nothing. Defaults to [HealthConnectExportSummary.EMPTY]
     * for a failure that never got as far as writing anything at all (permission revoked
     * mid-pass, a database read failing before the first batch).
     */
    data class Failed(val reason: String, val partialSummary: HealthConnectExportSummary = HealthConnectExportSummary.EMPTY) : HealthConnectExportOutcome
}

/**
 * What one pass actually wrote -- one count per record kind, never a single opaque total.
 * Counts the physical Health Connect records this app wrote, which can exceed one per
 * calendar day for [heartRateRecords]: a day whose sample count crosses Health Connect's
 * per-record cap is split across several records (see `splitHeartRateRecordIfOversized` in
 * [HealthConnectRecordMapper.kt][ch.kevinjordil.helion.healthconnect]), each counted here.
 */
data class HealthConnectExportSummary(
    val sleepSessions: Int,
    val exerciseSessions: Int,
    val heartRateRecords: Int,
    val stepsRecords: Int,
    val hrvRecords: Int,
    val spo2Records: Int,
    val temperatureRecords: Int,
    val respiratoryRateRecords: Int,
) {
    companion object {
        /** Every count zero -- what a [HealthConnectExportOutcome.Failed] carries when nothing was written before it failed. */
        val EMPTY = HealthConnectExportSummary(0, 0, 0, 0, 0, 0, 0, 0)
    }
}

private const val POINT_SERIES_HRV = "hrv"
private const val POINT_SERIES_SPO2 = "spo2"
private const val POINT_SERIES_TEMPERATURE = "temperature"
private const val POINT_SERIES_RESPIRATORY_RATE = "respiratory_rate"
private const val SECONDS_PER_DAY = 86_400L

/**
 * One export pass to Health Connect: reads whatever is new in Helion's own archive since
 * the last pass (see [HealthConnectExportState]), turns it into records via
 * [HealthConnectRecordMapper]'s pure functions, and writes them through [writerProvider] --
 * never touching [androidx.health.connect.client.HealthConnectClient] directly, so a test
 * can hand this a fake [HealthConnectWriter] and never start a real Health Connect service.
 *
 * The one rule this whole feature exists to enforce: [exportActivities] reads only
 * [ch.kevinjordil.helion.store.ActivityDao.confirmedOrPublished] -- never a
 * [ch.kevinjordil.helion.store.ActivityStatus.CANDIDATE] the owner has not looked at, and
 * never a [ch.kevinjordil.helion.store.ActivityStatus.DISMISSED] one. A detection the owner
 * has not validated must never reach another app; that is precisely the failure this
 * project exists to avoid, and Health Connect -- with Samsung Health reading everything it
 * holds -- is no exception.
 *
 * [cleanupDismissed] is the mirror of that rule for an activity dismissed *after* it was
 * already exported: it is removed from Health Connect too, by the same stable client
 * record id it was written under (see [ch.kevinjordil.helion.export.externalIdFor]), no
 * server-assigned id ever needed. As things stand this path is unreachable through the
 * app's own UI -- [ch.kevinjordil.helion.ui.activity.ActivityDetailScreen] offers no way to
 * move a [ch.kevinjordil.helion.store.ActivityStatus.CONFIRMED] or
 * [ch.kevinjordil.helion.store.ActivityStatus.PUBLISHED] activity to
 * [ch.kevinjordil.helion.store.ActivityStatus.DISMISSED] -- but the store itself does not
 * forbid it, and Health Connect's own delete API supports it cleanly, so this exporter
 * honours it anyway rather than leaving an orphaned record behind if that ever changes.
 *
 * Every other series (sleep, heart rate, steps, HRV, SpO2, skin temperature, respiratory
 * rate) is the device's own recording, not a judgement call, so it is written without any
 * per-item confirmation -- see this feature's own top-level brief.
 *
 * ### Batching
 *
 * Health Connect refuses a whole `insertRecords` call once its total serialised size passes
 * [HEALTH_CONNECT_MAX_CHUNK_BYTES] -- weeks of this device's minute-level data comfortably
 * exceeds that on its own, which is exactly the crash this class exists to avoid repeating.
 * [runPass] never hands the whole pass to one `insertOrUpdate` call: [batchBySize] groups the
 * records into batches under [HEALTH_CONNECT_BATCH_BYTE_BUDGET] first (see that file's own
 * kdoc for why the budget sits well under the real ceiling rather than against it), and each
 * batch is written in its own call.
 *
 * This is safe to interrupt. Every record this app ever writes carries a stable client
 * record id (see this file's own kdoc below on the `helion-<kind>-<id>` scheme), so a batch
 * Health Connect already has just gets updated in place the next time this pass -- or the
 * next pass, or a retry after a failure -- sends it again; nothing is ever duplicated by
 * writing the same batch twice. Watermarks and `*Written` counts are only persisted once
 * every batch in the pass has succeeded (see the end of [runPass]), so a pass that fails
 * partway through never advances anything: the next run re-reads and rebuilds the exact same
 * records from the exact same starting point, and Health Connect resolves the ones it already
 * has as updates rather than as new writes.
 *
 * That last property is also the answer to whether the *first* export -- the one that, on
 * enabling this feature, covers the whole archive at once and is by far the most likely to
 * need several batches -- deserves different handling from every incremental one after it: it
 * does not, deliberately. A bespoke "resume from batch N" path would save some redundant
 * re-sends on a retry, but it would do so by persisting progress *within* a pass, which is
 * exactly the kind of partial state this class otherwise avoids on purpose (see the
 * `*Written` fields' own kdoc on [ch.kevinjordil.helion.store.HealthConnectExportState]). The
 * plain retry-the-whole-pass design already converges correctly for a backlog of any size,
 * costing only some repeated (cheap, in-place) updates for whatever the previous attempt
 * already got through -- a real but bounded cost, not a correctness risk, and not worth the
 * extra state machine for a feature that runs in the background and is not time-critical.
 */
class HealthConnectExporter(
    private val db: HelionDatabase,
    private val config: HealthConnectConfig,
    /** Returns null when Health Connect cannot be written to at all right now -- not installed, too old, or the write itself is otherwise unavailable. */
    private val writerProvider: suspend () -> HealthConnectWriter?,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    suspend fun export(): HealthConnectExportOutcome {
        if (!config.enabled) return HealthConnectExportOutcome.Disabled
        val writer = writerProvider() ?: return HealthConnectExportOutcome.Unavailable
        if (!writer.hasWritePermission()) return HealthConnectExportOutcome.PermissionMissing

        val nowSeconds = now()
        return try {
            val summary = runPass(writer, nowSeconds)
            HealthConnectExportOutcome.Completed(summary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BatchWriteFailed) {
            recordFailure(nowSeconds, e.reason, e.partialSummary)
            HealthConnectExportOutcome.Failed(e.reason, e.partialSummary)
        } catch (e: Exception) {
            val reason = e.message ?: e::class.simpleName.orEmpty()
            recordFailure(nowSeconds, reason, HealthConnectExportSummary.EMPTY)
            HealthConnectExportOutcome.Failed(reason, HealthConnectExportSummary.EMPTY)
        }
    }

    /** One kind of record this exporter writes, tagging [TaggedRecord] so a batch can be credited to [HealthConnectExportSummary] by kind once it is actually written. */
    private enum class RecordKind { SLEEP, EXERCISE, HEART_RATE, STEPS, HRV, SPO2, TEMPERATURE, RESPIRATORY_RATE }

    private data class TaggedRecord(val record: Record, val kind: RecordKind)

    /** Thrown out of [writeBatches] when a batch fails partway through the pass, carrying what had already been written -- caught in [export] to build an honest [HealthConnectExportOutcome.Failed]. */
    private class BatchWriteFailed(val reason: String, val partialSummary: HealthConnectExportSummary) : Exception(reason)

    private suspend fun runPass(writer: HealthConnectWriter, nowSeconds: Long): HealthConnectExportSummary {
        val state = db.healthConnectExportState().get() ?: HealthConnectExportState()

        val sleep = exportSleepSessions(state, nowSeconds)
        val activities = exportActivities(nowSeconds)
        val minutes = exportHeartRateAndSteps(state, nowSeconds)
        val hrv = exportPointSeries(state.hrvWatermark, nowSeconds, POINT_SERIES_HRV, ::hrvRecordFor)
        val spo2 = exportPointSeries(state.spo2Watermark, nowSeconds, POINT_SERIES_SPO2, ::spo2RecordFor)
        val temperature = exportPointSeries(state.temperatureWatermark, nowSeconds, POINT_SERIES_TEMPERATURE, ::skinTemperatureRecordFor)
        val respiratoryRate = exportPointSeries(state.respiratoryRateWatermark, nowSeconds, POINT_SERIES_RESPIRATORY_RATE, ::respiratoryRateRecordFor)

        val tagged = mutableListOf<TaggedRecord>()
        tagRecords(tagged, sleep.records, RecordKind.SLEEP)
        tagRecords(tagged, activities.records, RecordKind.EXERCISE)
        tagRecords(tagged, minutes.heartRateRecords, RecordKind.HEART_RATE)
        tagRecords(tagged, minutes.stepsRecords, RecordKind.STEPS)
        tagRecords(tagged, hrv.records, RecordKind.HRV)
        tagRecords(tagged, spo2.records, RecordKind.SPO2)
        tagRecords(tagged, temperature.records, RecordKind.TEMPERATURE)
        tagRecords(tagged, respiratoryRate.records, RecordKind.RESPIRATORY_RATE)

        val summary = writeBatches(writer, tagged)

        markActivitiesPublished(activities.eligible, nowSeconds)
        cleanupDismissed(writer, nowSeconds)

        db.healthConnectExportState().put(
            state.copy(
                heartRateWatermark = minutes.newWatermark,
                hrvWatermark = hrv.newWatermark,
                spo2Watermark = spo2.newWatermark,
                temperatureWatermark = temperature.newWatermark,
                respiratoryRateWatermark = respiratoryRate.newWatermark,
                sleepSessionWatermark = sleep.newWatermark,
                lastRunAttempt = nowSeconds,
                lastError = null,
                sleepSessionsWritten = summary.sleepSessions,
                exerciseSessionsWritten = summary.exerciseSessions,
                heartRateRecordsWritten = summary.heartRateRecords,
                stepsRecordsWritten = summary.stepsRecords,
                hrvRecordsWritten = summary.hrvRecords,
                spo2RecordsWritten = summary.spo2Records,
                temperatureRecordsWritten = summary.temperatureRecords,
                respiratoryRateRecordsWritten = summary.respiratoryRateRecords,
            ),
        )

        return summary
    }

    /**
     * Tags every record in [records] as [kind], splitting a [HeartRateRecord] that exceeds
     * Health Connect's per-record sample cap into several tagged parts first (see
     * `splitHeartRateRecordIfOversized`) -- the only one of the record kinds this app writes
     * that can carry an oversized sample list on its own (see that function's own kdoc).
     */
    private fun tagRecords(into: MutableList<TaggedRecord>, records: List<Record>, kind: RecordKind) {
        for (record in records) {
            val parts = if (record is HeartRateRecord) splitHeartRateRecordIfOversized(record) else listOf(record)
            for (part in parts) into += TaggedRecord(part, kind)
        }
    }

    /**
     * Writes [tagged] to [writer] in batches under [HEALTH_CONNECT_BATCH_BYTE_BUDGET] (see
     * this class' own kdoc on batching), returning an honest [HealthConnectExportSummary] of
     * what actually made it -- every kind's count from every batch that succeeded. On a
     * failure partway through, throws [BatchWriteFailed] carrying that same partial summary
     * rather than losing track of what earlier, already-successful batches wrote.
     */
    private suspend fun writeBatches(writer: HealthConnectWriter, tagged: List<TaggedRecord>): HealthConnectExportSummary {
        val batches = batchBySize(tagged, HEALTH_CONNECT_BATCH_BYTE_BUDGET) { estimateRecordBytes(it.record) }
        val written = mutableMapOf<RecordKind, Int>()
        for (batch in batches) {
            try {
                writer.insertOrUpdate(batch.map { it.record })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw BatchWriteFailed(e.message ?: e::class.simpleName.orEmpty(), summaryFrom(written))
            }
            for (item in batch) written.merge(item.kind, 1, Int::plus)
        }
        return summaryFrom(written)
    }

    private fun summaryFrom(written: Map<RecordKind, Int>): HealthConnectExportSummary = HealthConnectExportSummary(
        sleepSessions = written[RecordKind.SLEEP] ?: 0,
        exerciseSessions = written[RecordKind.EXERCISE] ?: 0,
        heartRateRecords = written[RecordKind.HEART_RATE] ?: 0,
        stepsRecords = written[RecordKind.STEPS] ?: 0,
        hrvRecords = written[RecordKind.HRV] ?: 0,
        spo2Records = written[RecordKind.SPO2] ?: 0,
        temperatureRecords = written[RecordKind.TEMPERATURE] ?: 0,
        respiratoryRateRecords = written[RecordKind.RESPIRATORY_RATE] ?: 0,
    )

    private data class SleepExportResult(val records: List<SleepSessionRecord>, val newWatermark: Long)

    /**
     * Only ever reads sessions that have real device stage segments -- see
     * [sleepSessionRecordFor]'s own kdoc for why a night that fell back to Helion's own
     * estimate is never handed to it at all, since [ch.kevinjordil.helion.store.SleepStageSegmentDao.since]
     * itself only ever returns rows for a session the device actually reported.
     */
    private suspend fun exportSleepSessions(state: HealthConnectExportState, nowSeconds: Long): SleepExportResult {
        val newSegments = db.sleepStageSegments().since(state.sleepSessionWatermark)
        if (newSegments.isEmpty()) return SleepExportResult(emptyList(), state.sleepSessionWatermark)
        val records = newSegments.groupBy { it.sessionEnd }
            .mapNotNull { (sessionEnd, segments) -> sleepSessionRecordFor(sessionEnd, segments, nowSeconds) }
        val newWatermark = newSegments.maxOf { it.sessionEnd }
        return SleepExportResult(records, newWatermark)
    }

    private data class ActivityExportResult(
        val eligible: List<Activity>,
        val records: List<Record>,
    )

    /**
     * Every [ch.kevinjordil.helion.store.ActivityDao.confirmedOrPublished] activity, redone
     * in full on every pass rather than watermarked: this app is expected to hold at most a
     * few hundred activities ever, cheap to re-scan completely, and a plain re-scan is what
     * lets an edit to an already-exported activity (the owner renames it, or fixes its
     * notes, after export) reach Health Connect too -- the same stable
     * [ch.kevinjordil.helion.export.externalIdFor] client record id just resolves to an
     * update instead of a fresh insert.
     */
    private suspend fun exportActivities(nowSeconds: Long): ActivityExportResult {
        val eligible = db.activities().confirmedOrPublished()
        val records = mutableListOf<Record>()
        eligible.forEach { activity ->
            records += exerciseSessionRecordFor(activity, nowSeconds)
            val samples = db.minuteSamples().between(activity.startTimestamp, activity.endTimestamp)
            exerciseHeartRateRecordFor(activity, samples, nowSeconds)?.let { records += it }
        }
        return ActivityExportResult(eligible, records)
    }

    private suspend fun markActivitiesPublished(eligible: List<Activity>, nowSeconds: Long) {
        eligible.forEach { activity ->
            db.publications().upsert(
                Publication(
                    activityId = activity.id,
                    target = PublicationTarget.HEALTH_CONNECT,
                    remoteId = null,
                    state = PublicationState.PUBLISHED,
                    lastAttempt = nowSeconds,
                    lastError = null,
                ),
            )
        }
    }

    /**
     * Removes a dismissed activity's exercise session (and its own heart-rate record, if
     * one was written) from Health Connect -- see this class' own kdoc for why this path is
     * currently unreachable through the app's own UI, and kept anyway. Only attempted for an
     * activity whose [ch.kevinjordil.helion.store.Publication] row for
     * [PublicationTarget.HEALTH_CONNECT] is still [PublicationState.PUBLISHED]: one already
     * marked [PublicationState.REMOVED] is left alone, so a repeat pass never re-attempts a
     * deletion Health Connect would refuse for a record that no longer exists there.
     *
     * Each of the two deletes is attempted independently and failure is swallowed rather
     * than retried: the activity's own heart-rate record may never have existed (no
     * heart-rate data over its span) and deleting a client record id Health Connect has
     * never seen is documented to fail outright, not to be a silent no-op. Best-effort, the
     * same way [ch.kevinjordil.helion.source.Ingestor.runNotificationsOver] treats a
     * failure that must never abort the rest of a pass.
     */
    private suspend fun cleanupDismissed(writer: HealthConnectWriter, nowSeconds: Long) {
        val dismissed = db.activities().dismissed()
        for (activity in dismissed) {
            val publication = db.publications().get(activity.id, PublicationTarget.HEALTH_CONNECT) ?: continue
            if (publication.state != PublicationState.PUBLISHED) continue

            val deletions = listOf(
                ExerciseSessionRecord::class to externalIdFor(activity.id),
                HeartRateRecord::class to healthConnectExerciseHeartRateClientId(activity.id),
            )
            for ((recordType, clientId) in deletions) {
                try {
                    writer.deleteByClientId(recordType, listOf(clientId))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Best-effort -- see this function's own kdoc.
                }
            }
            db.publications().upsert(publication.copy(state = PublicationState.REMOVED, lastAttempt = nowSeconds, lastError = null))
        }
    }

    private data class MinutesExportResult(
        val heartRateRecords: List<HeartRateRecord>,
        val stepsRecords: List<StepsRecord>,
        val newWatermark: Long,
    )

    /**
     * Heart rate and steps, both derived from [ch.kevinjordil.helion.store.MinuteSample] and
     * both bucketed into one record per UTC calendar day (see [dailyHeartRateRecordFor] and
     * [dailyStepsRecordFor]). A day is always re-read in full from the store, never
     * incrementally: a UTC-day watermark alone cannot tell "today, still filling up" apart
     * from "yesterday, already complete", and only a full re-read guarantees the record this
     * pass writes is the true day total rather than just whatever arrived since the last
     * pass. The watermark itself only decides *which* days need redoing -- the newest
     * sample's own timestamp, so a day is revisited on every pass until nothing new arrives
     * for it -- never what goes into the record for a chosen day.
     */
    private suspend fun exportHeartRateAndSteps(state: HealthConnectExportState, nowSeconds: Long): MinutesExportResult {
        val newMinutes = db.minuteSamples().between(state.heartRateWatermark + 1, nowSeconds)
        if (newMinutes.isEmpty()) return MinutesExportResult(emptyList(), emptyList(), state.heartRateWatermark)

        val days = newMinutes.map { utcEpochDayOf(it.timestamp) }.distinct().sorted()
        val heartRateRecords = mutableListOf<HeartRateRecord>()
        val stepsRecords = mutableListOf<StepsRecord>()
        for (day in days) {
            val dayStart = day * SECONDS_PER_DAY
            val dayEnd = dayStart + SECONDS_PER_DAY - 1
            val dayMinutes = db.minuteSamples().between(dayStart, dayEnd)
            dailyHeartRateRecordFor(day, dayMinutes, nowSeconds)?.let { heartRateRecords += it }
            dailyStepsRecordFor(day, dayMinutes, nowSeconds)?.let { stepsRecords += it }
        }
        return MinutesExportResult(heartRateRecords, stepsRecords, newMinutes.maxOf { it.timestamp })
    }

    private data class PointSeriesExportResult<T : Record>(val records: List<T>, val newWatermark: Long)

    /** One point series (HRV, SpO2, skin temperature, respiratory rate), each reading its own record -- see [HealthConnectRecordMapper]. */
    private suspend fun <T : Record> exportPointSeries(
        watermark: Long,
        nowSeconds: Long,
        series: String,
        map: (PointSample, Long) -> T?,
    ): PointSeriesExportResult<T> {
        val newPoints = db.pointSamples().between(series, watermark + 1, nowSeconds)
        if (newPoints.isEmpty()) return PointSeriesExportResult(emptyList(), watermark)
        val records = newPoints.mapNotNull { map(it, nowSeconds) }
        return PointSeriesExportResult(records, newPoints.maxOf { it.timestamp })
    }

    /**
     * Persists [reason] as the pass' own failure and [partial] as what it actually managed
     * to write before that -- never a flat zero, so Réglages' own "last export" line stays
     * honest about a partial pass instead of implying nothing happened at all (see this
     * class' own kdoc on batching, and [ch.kevinjordil.helion.ui.settings.healthConnectStateMessage]
     * for where this is read back). Watermarks are left untouched -- see this class' own
     * kdoc on why a failed pass never advances anything.
     */
    private suspend fun recordFailure(nowSeconds: Long, reason: String, partial: HealthConnectExportSummary) {
        val state = db.healthConnectExportState().get() ?: HealthConnectExportState()
        db.healthConnectExportState().put(
            state.copy(
                lastRunAttempt = nowSeconds,
                lastError = reason,
                sleepSessionsWritten = partial.sleepSessions,
                exerciseSessionsWritten = partial.exerciseSessions,
                heartRateRecordsWritten = partial.heartRateRecords,
                stepsRecordsWritten = partial.stepsRecords,
                hrvRecordsWritten = partial.hrvRecords,
                spo2RecordsWritten = partial.spo2Records,
                temperatureRecordsWritten = partial.temperatureRecords,
                respiratoryRateRecordsWritten = partial.respiratoryRateRecords,
            ),
        )
    }
}
