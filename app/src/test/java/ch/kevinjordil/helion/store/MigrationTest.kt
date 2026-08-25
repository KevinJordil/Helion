package ch.kevinjordil.helion.store

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the real migration path against a real, file-backed database -- not the
 * in-memory one every other test uses -- because that is the only way to make Room actually
 * run [MIGRATION_1_2], [MIGRATION_2_3], [MIGRATION_3_4], [MIGRATION_4_5] and [MIGRATION_5_6] and validate the
 * resulting schema against what the entities declare. An in-memory `Room.databaseBuilder` is
 * always created fresh at the current version and never touches migration code at all.
 *
 * Building the version-1 database by hand from `schemas/.../1.json`'s own `createSql`
 * (rather than, say, checking in a real device's file) keeps this test free of any real
 * user data while still exercising the exact column set a v1 install would have on disk.
 * There is no `androidx.room:room-testing` dependency here (the project's dependencies are
 * pinned and this task must not add one), so this is the closest available substitute for
 * `MigrationTestHelper`.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dbName = "migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    /** Builds a database on disk exactly matching what a v1 install would have. */
    private fun seedVersion1Database() {
        context.deleteDatabase(dbName)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
        db.use {
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS `minute_sample` (`timestamp` INTEGER NOT NULL, " +
                    "`steps` INTEGER, `intensity` INTEGER, `rawKind` INTEGER, `heartRate` INTEGER, " +
                    "`sleepStage` INTEGER, PRIMARY KEY(`timestamp`))",
            )
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS `point_sample` (`series` TEXT NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL, `value` REAL NOT NULL, PRIMARY KEY(`series`, `timestamp`))",
            )
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS `sync_state` (`id` INTEGER NOT NULL, " +
                    "`lastIngestedTimestamp` INTEGER NOT NULL, `lastSyncAttempt` INTEGER NOT NULL, " +
                    "`lastError` TEXT, PRIMARY KEY(`id`))",
            )
            it.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            it.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
                    "VALUES(42, '2ba99b7dfac3d24f5968ae6ddb4d591d')",
            )

            // Health data that must survive the trip through both migrations untouched.
            it.execSQL(
                "INSERT INTO minute_sample (timestamp, steps, intensity, rawKind, heartRate, sleepStage) " +
                    "VALUES (100, 42, 1, 1, 60, 0)",
            )
            it.execSQL("INSERT INTO point_sample (series, timestamp, value) VALUES ('hrv', 50, 42.0)")
            it.execSQL(
                "INSERT INTO sync_state (id, lastIngestedTimestamp, lastSyncAttempt, lastError) " +
                    "VALUES (1, 999, 1234, 'previous error')",
            )
            it.setVersion(1)
        }
    }

    @Test
    fun `a version 1 database migrates to the current schema without losing rows`() = runTest {
        seedVersion1Database()

        val db = Room.databaseBuilder(context, HelionDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()

        try {
            // Forces Room to actually open the file, run all four migrations in order, and
            // validate the resulting schema against what the current entities declare --
            // this is where a mismatched migration would throw.
            assertEquals(1, db.minuteSamples().between(0, Long.MAX_VALUE).size)
            assertEquals(100L, db.minuteSamples().between(0, Long.MAX_VALUE).first().timestamp)

            assertEquals(1, db.pointSamples().between("hrv", 0, Long.MAX_VALUE).size)
            assertEquals(50L, db.pointSamples().between("hrv", 0, Long.MAX_VALUE).first().timestamp)

            // The dropped lastIngestedTimestamp column is gone, but everything MIGRATION_1_2
            // was meant to carry over survived, and the two new columns default to 0 for a
            // row that predates them, rather than crashing the migration or losing the row.
            val state = db.syncState().get()!!
            assertEquals(1234, state.lastSyncAttempt)
            assertEquals("previous error", state.lastError)
            assertEquals(0, state.triggerFailureStreak)
            assertEquals(0, state.lastTriggerAttempt)

            // MIGRATION_3_4 added this table from nothing; a fresh row must round-trip.
            db.sleepStageSegments().upsertAll(listOf(SleepStageSegment(1000, 900, 960, 4)))
            assertEquals(1, db.sleepStageSegments().overlapping(0, Long.MAX_VALUE).size)

            // MIGRATION_4_5 added activity/slot/publication from nothing; a fresh row on
            // each must round-trip, including the foreign key from activity to slot.
            val slotId = db.slots().upsert(
                Slot(
                    label = "Badminton",
                    dayOfWeek = java.time.DayOfWeek.TUESDAY,
                    startSecondOfDay = 72_000,
                    endSecondOfDay = 79_200,
                    sport = SportType.BADMINTON,
                ),
            )
            val activityId = db.activities().upsert(
                Activity(
                    startTimestamp = 1_000,
                    endTimestamp = 2_000,
                    sport = SportType.BADMINTON,
                    title = null,
                    notes = null,
                    origin = ActivityOrigin.SLOT,
                    status = ActivityStatus.CONFIRMED,
                    slotId = slotId,
                ),
            )
            assertEquals(1, db.activities().overlapping(1_000, 2_000).size)
            db.publications().upsert(
                Publication(
                    activityId = activityId,
                    target = PublicationTarget.STRAVA,
                    remoteId = null,
                    state = PublicationState.PENDING,
                    lastAttempt = null,
                    lastError = null,
                ),
            )
            assertEquals(1, db.publications().forActivity(activityId).size)

            // MIGRATION_5_6 added publication.uploadId from nothing; it must round-trip
            // on a row created after the migration ran.
            db.publications().upsert(
                Publication(
                    activityId = activityId,
                    target = PublicationTarget.STRAVA,
                    remoteId = null,
                    uploadId = "upload-123",
                    state = PublicationState.UPLOADING,
                    lastAttempt = 5_000,
                    lastError = null,
                ),
            )
            assertEquals("upload-123", db.publications().get(activityId, PublicationTarget.STRAVA)?.uploadId)
        } finally {
            db.close()
        }
    }

    @Test
    fun `a fresh install creates the current schema directly, no migration involved`() = runTest {
        context.deleteDatabase(dbName)
        val db = Room.databaseBuilder(context, HelionDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()

        try {
            // A brand new install has no prior version to migrate from: Room creates the
            // schema straight from the entities. Round-tripping a row here exercises that
            // path independently of the migrated one above.
            db.syncState().put(SyncState(lastSyncAttempt = 1, lastError = null))
            val state = db.syncState().get()!!
            assertEquals(1, state.lastSyncAttempt)
            assertNull(state.lastError)
            assertEquals(0, state.triggerFailureStreak)
            assertEquals(0, state.lastTriggerAttempt)
        } finally {
            db.close()
        }
    }
}
