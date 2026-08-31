package ch.kevinjordil.helion.healthconnect

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import ch.kevinjordil.helion.export.externalIdFor
import ch.kevinjordil.helion.source.DeviceSleepStage
import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.store.ActivityOrigin
import ch.kevinjordil.helion.store.ActivityStatus
import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.store.PointSample
import ch.kevinjordil.helion.store.SleepStageSegment
import ch.kevinjordil.helion.store.SportType
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_800_000_000L

class HealthConnectRecordMapperTest {

    // ---- sleep sessions ----

    @Test
    fun `a session with real device stages maps to a SleepSessionRecord with those stages`() {
        // endTimestamp names the START of the segment's last minute, not one second before
        // the next segment (see SleepStageSegment's own kdoc) -- a one-minute segment
        // therefore has startTimestamp == endTimestamp, both 0 for the first minute here.
        val segments = listOf(
            SleepStageSegment(sessionEnd = 1000, startTimestamp = 0, endTimestamp = 0, stage = DeviceSleepStage.LIGHT),
            SleepStageSegment(sessionEnd = 1000, startTimestamp = 60, endTimestamp = 60, stage = DeviceSleepStage.DEEP),
        )
        val record = sleepSessionRecordFor(sessionEnd = 1000, segments = segments, now = NOW)
        assertNotNull(record)
        assertEquals(2, record!!.stages.size)
        assertEquals(SleepSessionRecord.STAGE_TYPE_LIGHT, record.stages[0].stage)
        assertEquals(SleepSessionRecord.STAGE_TYPE_DEEP, record.stages[1].stage)
        assertEquals(Instant.ofEpochSecond(0), record.startTime)
        // +60: a device segment's endTimestamp is inclusive of its last minute; the record's
        // own end must be strictly after it.
        assertEquals(Instant.ofEpochSecond(120), record.endTime)
        assertEquals(healthConnectSleepSessionClientId(1000), record.metadata.clientRecordId)
    }

    @Test
    fun `no segments at all -- the estimated-only case -- never produces a record`() {
        assertNull(sleepSessionRecordFor(sessionEnd = 1000, segments = emptyList(), now = NOW))
    }

    @Test
    fun `a one-minute segment -- equal start and end timestamp -- still produces a valid record`() {
        val segments = listOf(SleepStageSegment(sessionEnd = 1000, startTimestamp = 500, endTimestamp = 500, stage = DeviceSleepStage.AWAKE))
        val record = sleepSessionRecordFor(sessionEnd = 1000, segments = segments, now = NOW)
        assertNotNull(record)
        assertTrue(record!!.endTime.isAfter(record.startTime))
    }

    // ---- exercise sessions ----

    private fun confirmedActivity(sport: SportType = SportType.BADMINTON, id: Long = 42) = Activity(
        id = id,
        startTimestamp = 1000,
        endTimestamp = 4600,
        sport = sport,
        title = "Match du soir",
        notes = "Bonne séance",
        origin = ActivityOrigin.MANUAL,
        status = ActivityStatus.CONFIRMED,
        detectionContext = "should never be exported",
    )

    @Test
    fun `an exercise session carries the activity's own client record id, title and notes but never its detection context`() {
        val record = exerciseSessionRecordFor(confirmedActivity(), NOW)
        assertEquals(externalIdFor(42), record.metadata.clientRecordId)
        assertEquals("Match du soir", record.title)
        assertEquals("Bonne séance", record.notes)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON, record.exerciseType)
    }

    @Test
    fun `sport mapping -- badminton is exact, cycling and swimming fall back, other maps to the generic workout type`() {
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON, healthConnectExerciseType(SportType.BADMINTON))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, healthConnectExerciseType(SportType.RUNNING))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_BIKING, healthConnectExerciseType(SportType.CYCLING))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, healthConnectExerciseType(SportType.WALKING))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, healthConnectExerciseType(SportType.SWIMMING))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, healthConnectExerciseType(SportType.OTHER))
    }

    @Test
    fun `the activity's own heart-rate record is null when the span has no heart-rate reading`() {
        val activity = confirmedActivity()
        val samples = listOf(MinuteSample(timestamp = 1000, steps = 10, intensity = null, rawKind = null, heartRate = null, sleepStage = null))
        assertNull(exerciseHeartRateRecordFor(activity, samples, NOW))
    }

    @Test
    fun `the activity's own heart-rate record carries only the samples with a real reading, under its own client id`() {
        val activity = confirmedActivity()
        val samples = listOf(
            MinuteSample(timestamp = 1000, steps = 10, intensity = null, rawKind = null, heartRate = 88, sleepStage = null),
            MinuteSample(timestamp = 1060, steps = 10, intensity = null, rawKind = null, heartRate = null, sleepStage = null),
        )
        val record = exerciseHeartRateRecordFor(activity, samples, NOW)
        assertNotNull(record)
        assertEquals(1, record!!.samples.size)
        assertEquals(88L, record.samples[0].beatsPerMinute)
        assertEquals(healthConnectExerciseHeartRateClientId(42), record.metadata.clientRecordId)
    }

    // ---- daily heart rate / steps ----

    @Test
    fun `daily heart rate and steps records are null for a day with no reading at all`() {
        val samples = listOf(MinuteSample(timestamp = 0, steps = null, intensity = null, rawKind = null, heartRate = null, sleepStage = null))
        assertNull(dailyHeartRateRecordFor(0, samples, NOW))
        assertNull(dailyStepsRecordFor(0, samples, NOW))
    }

    @Test
    fun `daily steps record sums the day's steps under a day-keyed client id`() {
        val epochDay = 100L
        val dayStart = epochDay * 86_400L
        val samples = listOf(
            MinuteSample(timestamp = dayStart, steps = 5, intensity = null, rawKind = null, heartRate = null, sleepStage = null),
            MinuteSample(timestamp = dayStart + 60, steps = 7, intensity = null, rawKind = null, heartRate = null, sleepStage = null),
        )
        val record = dailyStepsRecordFor(epochDay, samples, NOW)
        assertNotNull(record)
        assertEquals(12L, record!!.count)
        assertEquals(healthConnectDailyStepsClientId(epochDay), record.metadata.clientRecordId)
    }

    @Test
    fun `daily heart rate record keeps every reading of the day, dropping only null ones`() {
        val epochDay = 100L
        val dayStart = epochDay * 86_400L
        val samples = listOf(
            MinuteSample(timestamp = dayStart, steps = null, intensity = null, rawKind = null, heartRate = 70, sleepStage = null),
            MinuteSample(timestamp = dayStart + 60, steps = null, intensity = null, rawKind = null, heartRate = null, sleepStage = null),
            MinuteSample(timestamp = dayStart + 120, steps = null, intensity = null, rawKind = null, heartRate = 75, sleepStage = null),
        )
        val record = dailyHeartRateRecordFor(epochDay, samples, NOW)
        assertNotNull(record)
        assertEquals(2, record!!.samples.size)
        assertEquals(healthConnectDailyHeartRateClientId(epochDay), record.metadata.clientRecordId)
    }

    @Test
    fun `a heart-rate record within the per-record sample cap is returned unchanged`() {
        val samples = listOf(
            MinuteSample(timestamp = 0, steps = null, intensity = null, rawKind = null, heartRate = 70, sleepStage = null),
            MinuteSample(timestamp = 60, steps = null, intensity = null, rawKind = null, heartRate = 71, sleepStage = null),
        )
        val record = dailyHeartRateRecordFor(0, samples, NOW)!!
        val parts = splitHeartRateRecordIfOversized(record)
        assertEquals(1, parts.size)
        assertEquals(record, parts.single())
    }

    @Test
    fun `a heart-rate series longer than the per-record sample cap is split across several records, none over the cap`() {
        val sampleCount = 1_500
        val samples = (0 until sampleCount).map {
            MinuteSample(timestamp = it * 60L, steps = null, intensity = null, rawKind = null, heartRate = 70, sleepStage = null)
        }
        val record = dailyHeartRateRecordFor(0, samples, NOW)!!
        assertEquals(sampleCount, record.samples.size)

        val parts = splitHeartRateRecordIfOversized(record)

        assertTrue("expected more than one part for $sampleCount samples", parts.size > 1)
        for (part in parts) {
            assertTrue(part.samples.size <= 1000)
        }
        // Every original sample survives the split, none duplicated, order preserved.
        assertEquals(record.samples, parts.flatMap { it.samples })
        // Each part gets its own, stable client record id -- a re-run rebuilds and updates
        // the exact same ids rather than duplicating them.
        val ids = parts.map { it.metadata.clientRecordId }
        assertEquals(ids.toSet().size, ids.size)
        assertTrue(ids.all { it!!.startsWith(record.metadata.clientRecordId!!) })
        assertEquals(ids, splitHeartRateRecordIfOversized(record).map { it.metadata.clientRecordId })
    }

    @Test
    fun `utcEpochDayOf buckets a timestamp into the correct UTC calendar day`() {
        assertEquals(0L, utcEpochDayOf(0))
        assertEquals(0L, utcEpochDayOf(86_399))
        assertEquals(1L, utcEpochDayOf(86_400))
    }

    // ---- point series ----

    @Test
    fun `hrv, spo2 and respiratory rate map directly, under their own timestamp-keyed client id`() {
        val hrv = hrvRecordFor(PointSample("hrv", 500, 45.0), NOW)
        assertNotNull(hrv)
        assertEquals(45.0, hrv!!.heartRateVariabilityMillis, 0.0)
        assertEquals(healthConnectHrvClientId(500), hrv.metadata.clientRecordId)

        val spo2 = spo2RecordFor(PointSample("spo2", 500, 97.0), NOW)
        assertNotNull(spo2)
        assertEquals(97.0, spo2!!.percentage.value, 0.0)
        assertEquals(healthConnectSpo2ClientId(500), spo2.metadata.clientRecordId)

        val resp = respiratoryRateRecordFor(PointSample("respiratory_rate", 500, 16.0), NOW)
        assertNotNull(resp)
        assertEquals(16.0, resp!!.rate, 0.0)
        assertEquals(healthConnectRespiratoryRateClientId(500), resp.metadata.clientRecordId)
    }

    @Test
    fun `an hrv reading outside Health Connect's own 1-200ms range is never turned into a record`() {
        assertNull(hrvRecordFor(PointSample("hrv", 500, 0.5), NOW))
        assertNull(hrvRecordFor(PointSample("hrv", 500, 201.0), NOW))
    }

    @Test
    fun `skin temperature within the plausible range maps to a baseline with a single zero delta`() {
        val record = skinTemperatureRecordFor(PointSample("temperature", 500, 33.2), NOW)
        assertNotNull(record)
        assertEquals(33.2, record!!.baseline!!.inCelsius, 0.0)
        assertEquals(1, record.deltas.size)
        assertEquals(0.0, record.deltas[0].delta.inCelsius, 0.0)
        assertEquals(healthConnectSkinTemperatureClientId(500), record.metadata.clientRecordId)
    }

    @Test
    fun `an off-body skin temperature reading -- the strap on a table -- is never exported`() {
        assertNull(skinTemperatureRecordFor(PointSample("temperature", 500, 21.9), NOW))
        assertNull(skinTemperatureRecordFor(PointSample("temperature", 500, 43.0), NOW))
    }

    // ---- client record ids are stable across repeated calls ----

    @Test
    fun `every client record id is a pure function of identity, stable across repeated calls`() {
        assertEquals(healthConnectSleepSessionClientId(1000), healthConnectSleepSessionClientId(1000))
        assertEquals(externalIdFor(42), externalIdFor(42))
        assertEquals(healthConnectExerciseHeartRateClientId(42), healthConnectExerciseHeartRateClientId(42))
        assertEquals(healthConnectDailyHeartRateClientId(7), healthConnectDailyHeartRateClientId(7))
        assertEquals(healthConnectDailyStepsClientId(7), healthConnectDailyStepsClientId(7))
        assertEquals(healthConnectHrvClientId(500), healthConnectHrvClientId(500))
        assertEquals(healthConnectSpo2ClientId(500), healthConnectSpo2ClientId(500))
        assertEquals(healthConnectSkinTemperatureClientId(500), healthConnectSkinTemperatureClientId(500))
        assertEquals(healthConnectRespiratoryRateClientId(500), healthConnectRespiratoryRateClientId(500))
    }
}
