package ch.kevinjordil.helion.healthconnect

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.TemperatureDelta
import ch.kevinjordil.helion.export.activityDisplayName
import ch.kevinjordil.helion.export.externalIdFor
import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.store.PointSample
import ch.kevinjordil.helion.store.SleepStageSegment
import androidx.health.connect.client.records.metadata.Metadata
import ch.kevinjordil.helion.ui.metric.MetricCatalog
import ch.kevinjordil.helion.ui.sleep.devicePhaseOf
import ch.kevinjordil.helion.ui.sleep.SleepPhase
import java.time.Instant
import java.time.ZoneOffset

/**
 * Pure functions turning Helion's own archive rows into Health Connect's [Record] types.
 * Nothing here touches [androidx.health.connect.client.HealthConnectClient],
 * [android.content.Context], or any other Android framework type -- every function takes
 * plain store rows and a clock, and returns plain SDK record objects a test can inspect
 * field by field without ever starting a real Health Connect service. See
 * [HealthConnectExporter] for the orchestration (watermarks, permission checks, the actual
 * `insertRecords` call) built on top of these.
 */

/**
 * One night's [SleepSessionRecord], built directly from the device's own hypnogram
 * segments -- never from [ch.kevinjordil.helion.ui.sleep.segmentSleepEpisodes]'s
 * minute-derived estimate.
 *
 * This is a deliberate choice, not an oversight: a night with no `sleep_stage_segment` rows
 * (the device never reported a session, or the blob failed validation) falls back, inside
 * this app, to an estimate reconstructed from per-minute ASLEEP/AWAKE flags -- clearly
 * labelled as such wherever Helion itself shows it (see `SleepScreen`'s own estimated-phase
 * note). Health Connect's [SleepSessionRecord.Stage] carries no "this is a guess" flag, and
 * neither does whatever reads it next (Samsung Health, and anything else Health Connect
 * shares the record with): once written, an estimate would look exactly as authoritative as
 * a real measurement forever. So only a session backed by real device segments -- [segments]
 * non-empty here -- is ever turned into a record; an estimated night is simply never
 * written to Health Connect at all, the same call this app already makes for its own UI
 * (see [ch.kevinjordil.helion.ui.sleep.SleepReader]'s kdoc on why the device's own segments
 * are preferred whenever they exist).
 *
 * Returns null when [segments] is empty (nothing to build) or when every segment maps to no
 * known [SleepPhase] (should not happen for a validated session -- see
 * [ch.kevinjordil.helion.source.parseSleepSessionBlob] -- but this stays total rather than
 * risk an empty-stage record).
 */
fun sleepSessionRecordFor(sessionEnd: Long, segments: List<SleepStageSegment>, now: Long): SleepSessionRecord? {
    if (segments.isEmpty()) return null
    val stages = segments.mapNotNull { segment ->
        val phase = devicePhaseOf(segment.stage) ?: return@mapNotNull null
        // +60s: SleepStageSegment.endTimestamp is inclusive of the segment's last minute
        // (the device's own encoding, see that class' own kdoc), while Health Connect's
        // Stage requires a strictly-after, exclusive end -- a one-minute segment has
        // startTimestamp == endTimestamp and would otherwise fail that requirement outright.
        SleepSessionRecord.Stage(
            startTime = Instant.ofEpochSecond(segment.startTimestamp),
            endTime = Instant.ofEpochSecond(segment.endTimestamp + 60),
            stage = healthConnectSleepStageType(phase),
        )
    }
    if (stages.isEmpty()) return null
    val start = stages.minOf { it.startTime }
    val end = stages.maxOf { it.endTime }
    if (!end.isAfter(start)) return null
    return SleepSessionRecord(
        startTime = start,
        startZoneOffset = null,
        endTime = end,
        endZoneOffset = null,
        metadata = healthConnectMetadata(healthConnectSleepSessionClientId(sessionEnd), now),
        title = null,
        notes = null,
        stages = stages,
    )
}

/** [SleepPhase] to [SleepSessionRecord]'s own `STAGE_TYPE_*` vocabulary -- a direct, lossless match, all four exist on both sides. */
fun healthConnectSleepStageType(phase: SleepPhase): Int = when (phase) {
    SleepPhase.AWAKE -> SleepSessionRecord.STAGE_TYPE_AWAKE
    SleepPhase.LIGHT -> SleepSessionRecord.STAGE_TYPE_LIGHT
    SleepPhase.DEEP -> SleepSessionRecord.STAGE_TYPE_DEEP
    SleepPhase.REM -> SleepSessionRecord.STAGE_TYPE_REM
}

/**
 * One [ExerciseSessionRecord] for a confirmed activity -- see [HealthConnectExporter] for
 * the status check this relies on; this function itself does not look at
 * [Activity.status] at all and trusts its caller never to hand it a candidate or a
 * dismissed one. [clientRecordId] is [ch.kevinjordil.helion.export.externalIdFor], the
 * exact same stable id the custom-server target already uses for this activity -- one
 * identity, reused everywhere this activity is ever sent.
 *
 * Requires [Activity.sport] to be non-null: [HealthConnectExporter.exportActivities] filters
 * to sport-bearing activities before ever calling this, the same refusal every other export
 * path applies to a sportless activity (see [Activity.sport]'s own kdoc) -- this function
 * stays a total mapping over what it is actually handed rather than silently guessing an
 * `exerciseType` for a row that should never have reached it.
 */
fun exerciseSessionRecordFor(activity: Activity, now: Long): ExerciseSessionRecord =
    ExerciseSessionRecord(
        startTime = Instant.ofEpochSecond(activity.startTimestamp),
        startZoneOffset = null,
        endTime = Instant.ofEpochSecond(activity.endTimestamp),
        endZoneOffset = null,
        metadata = healthConnectMetadata(externalIdFor(activity.id), now),
        exerciseType = healthConnectExerciseType(
            requireNotNull(activity.sport) { "activity ${activity.id} has no sport -- the caller must filter these out first" },
        ),
        title = activityDisplayName(activity),
        // Never activity.detectionContext -- see ch.kevinjordil.helion.export.PublishingSupport's
        // own kdoc for why that diagnostic text must never leave this app.
        notes = activity.notes,
    )

/**
 * The heart-rate series for one activity's own span, as its own [HeartRateRecord] -- a
 * separate record from the day-level one [dailyHeartRateRecordFor] produces, the same way a
 * workout's own heart-rate track sits alongside (not instead of) the all-day series in
 * every other Health Connect writer. Null when [samples] carries no heart-rate reading at
 * all over the span (nothing worth a record with zero samples).
 */
fun exerciseHeartRateRecordFor(activity: Activity, samples: List<MinuteSample>, now: Long): HeartRateRecord? {
    val readings = samples.mapNotNull { sample -> sample.heartRate?.let { sample.timestamp to it } }
    if (readings.isEmpty()) return null
    return HeartRateRecord(
        startTime = Instant.ofEpochSecond(activity.startTimestamp),
        startZoneOffset = null,
        endTime = Instant.ofEpochSecond(activity.endTimestamp),
        endZoneOffset = null,
        samples = readings.map { (timestamp, bpm) ->
            HeartRateRecord.Sample(time = Instant.ofEpochSecond(timestamp), beatsPerMinute = bpm.toLong())
        },
        metadata = healthConnectMetadata(healthConnectExerciseHeartRateClientId(activity.id), now),
    )
}

/**
 * One calendar day's (UTC) heart-rate readings as a single [HeartRateRecord]. [samples]
 * must already be filtered to the one day this record is for -- see [HealthConnectExporter]
 * for why a day, not the whole new range, is the unit of re-computation. Null when the day
 * has no heart-rate reading at all (a day with only step counts, say).
 */
fun dailyHeartRateRecordFor(epochDay: Long, samples: List<MinuteSample>, now: Long): HeartRateRecord? {
    val readings = samples.mapNotNull { sample -> sample.heartRate?.let { sample.timestamp to it } }
    if (readings.isEmpty()) return null
    val (dayStart, dayEnd) = dayBoundsUtc(epochDay)
    return HeartRateRecord(
        startTime = dayStart,
        startZoneOffset = ZoneOffset.UTC,
        endTime = dayEnd,
        endZoneOffset = ZoneOffset.UTC,
        samples = readings.sortedBy { it.first }.map { (timestamp, bpm) ->
            HeartRateRecord.Sample(time = Instant.ofEpochSecond(timestamp), beatsPerMinute = bpm.toLong())
        },
        metadata = healthConnectMetadata(healthConnectDailyHeartRateClientId(epochDay), now),
    )
}

/**
 * Health Connect's own per-request byte ceiling (see [HealthConnectExporter]'s own kdoc for
 * the crash this whole batching effort exists to fix) applies to the *whole* insert call, not
 * to one record on its own -- but a single [HeartRateRecord] carrying a very long
 * [HeartRateRecord.samples] list can still be disproportionately large next to every other
 * record in a batch, and Health Connect's own developer guidance settles on 1000 as the unit
 * it batches everything around ("chunk requests to at most 1000 records per write request").
 * There is no separately published ceiling on samples *within* one series record, so this
 * reuses that same figure as a conservative per-record cap: it is not an arbitrary number,
 * and it does real work here -- this device samples heart rate once a minute, so a full UTC
 * day ([dailyHeartRateRecordFor]) can carry up to 1440 samples, comfortably over this cap,
 * while a typical activity's own span ([exerciseHeartRateRecordFor]) rarely comes close.
 */
private const val MAX_SAMPLES_PER_HEART_RATE_RECORD = 1000

/**
 * Splits [record] into several [HeartRateRecord]s of at most [MAX_SAMPLES_PER_HEART_RATE_RECORD]
 * samples each when it exceeds that cap, or returns it unchanged (as the only element of a
 * one-item list) otherwise. [record.samples] is assumed already sorted by time -- both
 * [dailyHeartRateRecordFor] and [exerciseHeartRateRecordFor] sort or naturally produce theirs
 * in order -- so consecutive chunks never overlap in time.
 *
 * Each part gets its own client record id, built from [record]'s own id with a stable
 * `-partN` suffix: deterministic given the same (sorted, unsplit) sample list, so a re-run
 * produces the exact same part ids and updates them in place rather than duplicating them --
 * the same stability [HealthConnectExporter]'s whole batching story depends on.
 */
fun splitHeartRateRecordIfOversized(record: HeartRateRecord): List<HeartRateRecord> {
    if (record.samples.size <= MAX_SAMPLES_PER_HEART_RATE_RECORD) return listOf(record)
    return record.samples.chunked(MAX_SAMPLES_PER_HEART_RATE_RECORD).mapIndexed { index, chunk ->
        HeartRateRecord(
            startTime = chunk.first().time,
            startZoneOffset = record.startZoneOffset,
            endTime = chunk.last().time,
            endZoneOffset = record.endZoneOffset,
            samples = chunk,
            metadata = Metadata.autoRecorded(
                device = HELION_DEVICE,
                clientRecordId = "${record.metadata.clientRecordId}-part$index",
                clientRecordVersion = record.metadata.clientRecordVersion,
            ),
        )
    }
}

/**
 * One calendar day's (UTC) step total as a single [StepsRecord] -- the same "a daily total
 * is what is actually meaningful" call [ch.kevinjordil.helion.ui.metric.Aggregation.DAILY_SUM]
 * already makes for this app's own steps display, applied here to what leaves the device
 * too. Null when the day has no step reading at all (every [MinuteSample.steps] null).
 */
fun dailyStepsRecordFor(epochDay: Long, samples: List<MinuteSample>, now: Long): StepsRecord? {
    val steps = samples.mapNotNull { it.steps }
    if (steps.isEmpty()) return null
    val total = steps.sum().toLong()
    // StepsRecord.count's own valid range is 1-1,000,000 -- a day with nothing recorded
    // (0) has no meaningful record to write, and this device is nowhere near the upper
    // bound, but the guard is cheap and keeps this series' own discipline: never hand a
    // record the SDK itself would refuse to construct.
    if (total !in 1..1_000_000) return null
    val (dayStart, dayEnd) = dayBoundsUtc(epochDay)
    return StepsRecord(
        startTime = dayStart,
        startZoneOffset = ZoneOffset.UTC,
        endTime = dayEnd,
        endZoneOffset = ZoneOffset.UTC,
        count = total,
        metadata = healthConnectMetadata(healthConnectDailyStepsClientId(epochDay), now),
    )
}

/** [epochDay]'s own `[start, end)` window in UTC, as Instants -- shared by every daily-bucket record above. */
private fun dayBoundsUtc(epochDay: Long): Pair<Instant, Instant> {
    val start = Instant.ofEpochSecond(epochDay * SECONDS_PER_DAY)
    val end = start.plusSeconds(SECONDS_PER_DAY)
    return start to end
}

/** The UTC calendar day a timestamp (Unix seconds) falls in, as an epoch-day number -- the same bucketing key [dayBoundsUtc] reverses. */
fun utcEpochDayOf(timestamp: Long): Long = Math.floorDiv(timestamp, SECONDS_PER_DAY)

private const val SECONDS_PER_DAY = 86_400L

/**
 * One [HeartRateVariabilityRmssdRecord] for a single `hrv` [PointSample] -- ms, already the
 * unit both sides use. Null outside 1.0-200.0, [HeartRateVariabilityRmssdRecord]'s own valid
 * range: constructing one outside it throws, and Health Connect's `insertRecords` is a
 * single transaction over the whole batch handed to it -- one bad reading must never be
 * allowed to take an entire pass's worth of otherwise-good records down with it, so this is
 * filtered before construction rather than left to throw.
 */
fun hrvRecordFor(sample: PointSample, now: Long): HeartRateVariabilityRmssdRecord? {
    if (sample.value !in HRV_VALID_RANGE) return null
    return HeartRateVariabilityRmssdRecord(
        time = Instant.ofEpochSecond(sample.timestamp),
        zoneOffset = null,
        heartRateVariabilityMillis = sample.value,
        metadata = healthConnectMetadata(healthConnectHrvClientId(sample.timestamp), now),
    )
}

/**
 * One [OxygenSaturationRecord] for a single `spo2` [PointSample] -- already a 0-100
 * percentage on both sides. Null outside that range -- see [hrvRecordFor]'s own kdoc for why
 * this is filtered rather than left to throw.
 */
fun spo2RecordFor(sample: PointSample, now: Long): OxygenSaturationRecord? {
    if (sample.value !in SPO2_VALID_RANGE) return null
    return OxygenSaturationRecord(
        time = Instant.ofEpochSecond(sample.timestamp),
        zoneOffset = null,
        percentage = Percentage(sample.value),
        metadata = healthConnectMetadata(healthConnectSpo2ClientId(sample.timestamp), now),
    )
}

/**
 * One [RespiratoryRateRecord] for a single `respiratory_rate` [PointSample] -- breaths per
 * minute on both sides. Null outside 0.0-1000.0 -- see [hrvRecordFor]'s own kdoc for why
 * this is filtered rather than left to throw; this device's real readings sit nowhere near
 * that ceiling, but the guard costs nothing and keeps the same discipline every series here
 * follows.
 */
fun respiratoryRateRecordFor(sample: PointSample, now: Long): RespiratoryRateRecord? {
    if (sample.value !in RESPIRATORY_RATE_VALID_RANGE) return null
    return RespiratoryRateRecord(
        time = Instant.ofEpochSecond(sample.timestamp),
        zoneOffset = null,
        rate = sample.value,
        metadata = healthConnectMetadata(healthConnectRespiratoryRateClientId(sample.timestamp), now),
    )
}

private val HRV_VALID_RANGE = 1.0..200.0
private val SPO2_VALID_RANGE = 0.0..100.0
private val RESPIRATORY_RATE_VALID_RANGE = 0.0..1000.0

/**
 * One [SkinTemperatureRecord] for a single `temperature` [PointSample], or null when
 * [sample] falls outside [MetricCatalog.MIN_PLAUSIBLE_SKIN_TEMPERATURE]..
 * [MetricCatalog.MAX_PLAUSIBLE_SKIN_TEMPERATURE] -- the exact same cut
 * [ch.kevinjordil.helion.ui.metric.MetricCatalog] applies before showing this series at
 * all, see that catalog's own kdoc for why: an off-body reading (the strap sitting on a
 * table, at room temperature) must not reach a shared store looking like a real skin
 * temperature any more than it should reach this app's own chart.
 *
 * Health Connect's [SkinTemperatureRecord] models every reading as a delta from a
 * *baseline* temperature for the whole interval, not a plain absolute reading -- there is
 * no "just a temperature, right now" record type. This device reports one absolute value
 * per reading with no separate baseline of its own, and inventing one (a rolling average,
 * say) would be presenting a number Helion never actually measured. The reading itself is
 * used as [SkinTemperatureRecord.baseline], with a single [SkinTemperatureRecord.Delta] of
 * zero at the same instant: the record's own two numbers -- baseline plus a zero offset --
 * multiply out to exactly the one value this app actually has, without fabricating a trend
 * neither this reading nor the one before it can support. [SkinTemperatureRecord] also
 * requires a `measurementLocation`; [SkinTemperatureRecord.MEASUREMENT_LOCATION_WRIST] is
 * the honest one for a strap, where the other three device-defined constants are for a
 * different body part [SkinTemperatureRecord.MEASUREMENT_LOCATION_UNKNOWN] would just as
 * plausibly fit, but the strap's actual placement is not, in fact, unknown.
 */
fun skinTemperatureRecordFor(sample: PointSample, now: Long): SkinTemperatureRecord? {
    if (sample.value !in MetricCatalog.MIN_PLAUSIBLE_SKIN_TEMPERATURE..MetricCatalog.MAX_PLAUSIBLE_SKIN_TEMPERATURE) return null
    val time = Instant.ofEpochSecond(sample.timestamp)
    return SkinTemperatureRecord(
        startTime = time,
        startZoneOffset = null,
        endTime = time.plusSeconds(1),
        endZoneOffset = null,
        metadata = healthConnectMetadata(healthConnectSkinTemperatureClientId(sample.timestamp), now),
        deltas = listOf(SkinTemperatureRecord.Delta(time = time, delta = TemperatureDelta.celsius(0.0))),
        baseline = Temperature.celsius(sample.value),
        measurementLocation = SkinTemperatureRecord.MEASUREMENT_LOCATION_WRIST,
    )
}
