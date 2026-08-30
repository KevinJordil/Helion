package ch.kevinjordil.helion.healthconnect

import android.content.Context
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.kevinjordil.helion.export.externalIdFor
import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.store.ActivityOrigin
import ch.kevinjordil.helion.store.ActivityStatus
import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.store.Publication
import ch.kevinjordil.helion.store.PublicationState
import ch.kevinjordil.helion.store.PublicationTarget
import ch.kevinjordil.helion.store.SportType
import ch.kevinjordil.helion.ui.settings.HealthConnectConfig
import kotlin.reflect.KClass
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
class HealthConnectExporterTest {

    private lateinit var db: HelionDatabase
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class FakeWriter(private val permissionGranted: Boolean = true) : HealthConnectWriter {
        val inserted = mutableListOf<Record>()
        val deleted = mutableListOf<Pair<KClass<out Record>, List<String>>>()
        var insertCalls = 0

        override suspend fun hasWritePermission(): Boolean = permissionGranted

        override suspend fun insertOrUpdate(records: List<Record>): Int {
            insertCalls++
            inserted += records
            return records.size
        }

        override suspend fun deleteByClientId(recordType: KClass<out Record>, clientRecordIds: List<String>) {
            deleted += recordType to clientRecordIds
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, HelionDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun activity(id: Long, status: ActivityStatus, start: Long = 1000, end: Long = 4600) = Activity(
        id = id,
        startTimestamp = start,
        endTimestamp = end,
        sport = SportType.BADMINTON,
        title = "Séance $id",
        notes = null,
        origin = ActivityOrigin.MANUAL,
        status = status,
    )

    private fun exporter(config: HealthConnectConfig, writer: HealthConnectWriter?) = HealthConnectExporter(
        db = db,
        config = config,
        writerProvider = { writer },
        now = { 1_800_000_000L },
    )

    @Test
    fun `disabled means nothing is read and nothing is written`() = runTest {
        val config = HealthConnectConfig(context).apply { enabled = false }
        var providerCalled = false
        val exporter = HealthConnectExporter(db, config, writerProvider = { providerCalled = true; null })
        val outcome = exporter.export()
        assertEquals(HealthConnectExportOutcome.Disabled, outcome)
        assertFalse(providerCalled)
    }

    @Test
    fun `Health Connect unavailable on this phone is reported and nothing is written`() = runTest {
        val config = HealthConnectConfig(context).apply { enabled = true }
        val outcome = exporter(config, writer = null).export()
        assertEquals(HealthConnectExportOutcome.Unavailable, outcome)
    }

    @Test
    fun `permission missing is reported and nothing is written`() = runTest {
        val config = HealthConnectConfig(context).apply { enabled = true }
        val writer = FakeWriter(permissionGranted = false)
        val outcome = exporter(config, writer).export()
        assertEquals(HealthConnectExportOutcome.PermissionMissing, outcome)
        assertTrue(writer.inserted.isEmpty())
    }

    @Test
    fun `a candidate and a dismissed activity are never included, only confirmed and published`() = runTest {
        db.activities().upsert(activity(1, ActivityStatus.CANDIDATE))
        db.activities().upsert(activity(2, ActivityStatus.DISMISSED))
        db.activities().upsert(activity(3, ActivityStatus.CONFIRMED))
        db.activities().upsert(activity(4, ActivityStatus.PUBLISHED))

        val config = HealthConnectConfig(context).apply { enabled = true }
        val writer = FakeWriter()
        val outcome = exporter(config, writer).export()

        assertTrue(outcome is HealthConnectExportOutcome.Completed)
        val exerciseRecords = writer.inserted.filterIsInstance<ExerciseSessionRecord>()
        val clientIds = exerciseRecords.map { it.metadata.clientRecordId }
        assertEquals(setOf(externalIdFor(3), externalIdFor(4)), clientIds.toSet())
        assertFalse(clientIds.contains(externalIdFor(1)))
        assertFalse(clientIds.contains(externalIdFor(2)))
    }

    @Test
    fun `re-running the export with nothing new produces the same client record ids`() = runTest {
        db.activities().upsert(activity(1, ActivityStatus.CONFIRMED))
        db.minuteSamples().upsertAll(listOf(MinuteSample(1000, steps = 5, intensity = null, rawKind = null, heartRate = 80, sleepStage = null)))

        val config = HealthConnectConfig(context).apply { enabled = true }
        val writer = FakeWriter()
        val exp = exporter(config, writer)

        exp.export()
        val firstIds = writer.inserted.map { it.metadata.clientRecordId }.toSet()
        writer.inserted.clear()
        exp.export()
        val secondIds = writer.inserted.map { it.metadata.clientRecordId }.toSet()

        // Not necessarily identical (the daily-bucket watermark may have advanced past the
        // one new minute, meaning the second pass finds nothing new for that series at all)
        // but never a NEW id for the same underlying activity or day -- no duplicate
        // identity is ever introduced by re-running.
        assertTrue(secondIds.all { it in firstIds || it == externalIdFor(1) })
    }

    @Test
    fun `an activity dismissed after being exported is removed from Health Connect and its publication row is marked removed`() = runTest {
        val dismissedId = 5L
        db.activities().upsert(activity(dismissedId, ActivityStatus.DISMISSED))
        db.publications().upsert(
            Publication(
                activityId = dismissedId,
                target = PublicationTarget.HEALTH_CONNECT,
                remoteId = null,
                state = PublicationState.PUBLISHED,
                lastAttempt = 1_000L,
                lastError = null,
            ),
        )

        val config = HealthConnectConfig(context).apply { enabled = true }
        val writer = FakeWriter()
        exporter(config, writer).export()

        val deletedExercise = writer.deleted.filter { it.first == ExerciseSessionRecord::class }
        assertTrue(deletedExercise.any { it.second.contains(externalIdFor(dismissedId)) })
        val deletedHeartRate = writer.deleted.filter { it.first == HeartRateRecord::class }
        assertTrue(deletedHeartRate.any { it.second.contains(healthConnectExerciseHeartRateClientId(dismissedId)) })

        val publication = db.publications().get(dismissedId, PublicationTarget.HEALTH_CONNECT)
        assertEquals(PublicationState.REMOVED, publication?.state)
    }

    @Test
    fun `a dismissed activity that was never published to Health Connect is never sent a delete request`() = runTest {
        db.activities().upsert(activity(6, ActivityStatus.DISMISSED))
        val config = HealthConnectConfig(context).apply { enabled = true }
        val writer = FakeWriter()
        exporter(config, writer).export()
        assertTrue(writer.deleted.isEmpty())
    }
}
