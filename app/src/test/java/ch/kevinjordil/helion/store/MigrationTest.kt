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
 * run [MIGRATION_1_2], [MIGRATION_2_3], [MIGRATION_3_4], [MIGRATION_4_5], [MIGRATION_5_6],
 * [MIGRATION_6_7] and [MIGRATION_7_8] and validate the resulting schema against what the
 * entities declare. An in-memory `Room.databaseBuilder` is
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
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

            // MIGRATION_6_7 added publication.lastErrorDetail from nothing; it must
            // round-trip on a row created after the migration ran.
            db.publications().upsert(
                Publication(
                    activityId = activityId,
                    target = PublicationTarget.STRAVA,
                    remoteId = null,
                    uploadId = null,
                    state = PublicationState.FAILED,
                    lastAttempt = 6_000,
                    lastError = "remote_error",
                    lastErrorDetail = "Bad Request (Upload data_type invalid)",
                ),
            )
            assertEquals(
                "Bad Request (Upload data_type invalid)",
                db.publications().get(activityId, PublicationTarget.STRAVA)?.lastErrorDetail,
            )

            // MIGRATION_9_10 added publication.lastMessage from nothing; it must
            // round-trip on a row created after the migration ran.
            db.publications().upsert(
                Publication(
                    activityId = activityId,
                    target = PublicationTarget.CUSTOM_SERVER,
                    remoteId = null,
                    uploadId = null,
                    state = PublicationState.PUBLISHED,
                    lastAttempt = 7_000,
                    lastError = null,
                    lastMessage = "HTTP 202: Activité reçue.",
                ),
            )
            assertEquals(
                "HTTP 202: Activité reçue.",
                db.publications().get(activityId, PublicationTarget.CUSTOM_SERVER)?.lastMessage,
            )
        } finally {
            db.close()
        }
    }

    /**
     * Builds a database on disk exactly matching what a v7 install would have -- every
     * table's `createSql` straight from `schemas/.../7.json`, same reasoning as
     * [seedVersion1Database] -- with real rows only in `activity`, the one table
     * [MIGRATION_7_8] touches; the rest exist purely so Room's post-migration schema
     * validation (which checks every entity, not just the ones a migration wrote to) has
     * something real to validate against.
     */
    private fun seedVersion7Database() {
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
                    "`lastSyncAttempt` INTEGER NOT NULL, `lastError` TEXT, " +
                    "`triggerFailureStreak` INTEGER NOT NULL, `lastTriggerAttempt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))",
            )
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS `sleep_stage_segment` (`sessionEnd` INTEGER NOT NULL, " +
                    "`startTimestamp` INTEGER NOT NULL, `endTimestamp` INTEGER NOT NULL, " +
                    "`stage` INTEGER NOT NULL, PRIMARY KEY(`sessionEnd`, `startTimestamp`))",
            )
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS `slot` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`label` TEXT NOT NULL, `dayOfWeek` TEXT NOT NULL, `startSecondOfDay` INTEGER NOT NULL, " +
                    "`endSecondOfDay` INTEGER NOT NULL, `sport` TEXT NOT NULL, `active` INTEGER NOT NULL)",
            )
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS `activity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`startTimestamp` INTEGER NOT NULL, `endTimestamp` INTEGER NOT NULL, `sport` TEXT NOT NULL, " +
                    "`title` TEXT, `notes` TEXT, `origin` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                    "`slotId` INTEGER, FOREIGN KEY(`slotId`) REFERENCES `slot`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE SET NULL )",
            )
            it.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_activity_startTimestamp_endTimestamp` " +
                    "ON `activity` (`startTimestamp`, `endTimestamp`)",
            )
            it.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_status` ON `activity` (`status`)")
            it.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_slotId` ON `activity` (`slotId`)")
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS `publication` (`activityId` INTEGER NOT NULL, " +
                    "`target` TEXT NOT NULL, `remoteId` TEXT, `uploadId` TEXT, `state` TEXT NOT NULL, " +
                    "`lastAttempt` INTEGER, `lastError` TEXT, `lastErrorDetail` TEXT, " +
                    "PRIMARY KEY(`activityId`, `target`), FOREIGN KEY(`activityId`) REFERENCES " +
                    "`activity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            it.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            it.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
                    "VALUES(42, 'bb23faeb4db599512c1043a36c2a27dc')",
            )

            // A SLOT-origin candidate whose notes are exactly detection's own, unedited
            // sentence -- what every real candidate on the owner's phone looks like today.
            it.execSQL(
                "INSERT INTO activity (id, startTimestamp, endTimestamp, sport, title, notes, origin, status, slotId) " +
                    "VALUES (1, 1000, 2000, 'BADMINTON', 'Entraînement du lundi', " +
                    "'Fréquence cardiaque 120–150 bpm (repos habituel ≈ 58 bpm).', 'SLOT', 'CANDIDATE', NULL)",
            )
            // A DETECTED-origin candidate, same shape.
            it.execSQL(
                "INSERT INTO activity (id, startTimestamp, endTimestamp, sport, title, notes, origin, status, slotId) " +
                    "VALUES (2, 3000, 4000, 'OTHER', NULL, " +
                    "'Fréquence cardiaque 90–110 bpm (repos habituel ≈ 55 bpm).', 'DETECTED', 'CANDIDATE', NULL)",
            )
            // A SLOT-origin activity the owner has since confirmed and written his own
            // words into -- must be left exactly as it is, never touched or lost.
            it.execSQL(
                "INSERT INTO activity (id, startTimestamp, endTimestamp, sport, title, notes, origin, status, slotId) " +
                    "VALUES (3, 5000, 6000, 'BADMINTON', 'Entraînement du lundi', " +
                    "'Bonne séance, jambes lourdes.', 'SLOT', 'CONFIRMED', NULL)",
            )
            // A MANUAL activity whose own notes happen to match detection's exact wording
            // shape -- detection never wrote to this row (only SLOT/DETECTED rows ever
            // get a rendered note), so origin alone must keep it untouched even though the
            // text itself would otherwise match.
            it.execSQL(
                "INSERT INTO activity (id, startTimestamp, endTimestamp, sport, title, notes, origin, status, slotId) " +
                    "VALUES (4, 7000, 8000, 'RUNNING', 'Sortie du dimanche', " +
                    "'Fréquence cardiaque plus élevée que d''habitude (repos habituel ≈ 55 bpm).', 'MANUAL', 'CONFIRMED', NULL)",
            )
            // A candidate with no notes at all -- the common case once this fix ships.
            it.execSQL(
                "INSERT INTO activity (id, startTimestamp, endTimestamp, sport, title, notes, origin, status, slotId) " +
                    "VALUES (5, 9000, 10000, 'BADMINTON', 'Entraînement du lundi', NULL, 'SLOT', 'CANDIDATE', NULL)",
            )
            it.setVersion(7)
        }
    }

    @Test
    fun `migrating from version 7 moves only unedited detection text out of notes, and loses no row`() = runTest {
        seedVersion7Database()

        val db = Room.databaseBuilder(context, HelionDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
            .allowMainThreadQueries()
            .build()

        try {
            val all = db.activities().all().associateBy { it.id }
            assertEquals(5, all.size)

            // Unedited detection text: moved to detectionContext, notes cleared.
            val slotCandidate = all.getValue(1)
            assertEquals("Entraînement du lundi", slotCandidate.title)
            assertNull(slotCandidate.notes)
            assertEquals(
                "Fréquence cardiaque 120–150 bpm (repos habituel ≈ 58 bpm).",
                slotCandidate.detectionContext,
            )

            val detectedCandidate = all.getValue(2)
            assertNull(detectedCandidate.notes)
            assertEquals(
                "Fréquence cardiaque 90–110 bpm (repos habituel ≈ 55 bpm).",
                detectedCandidate.detectionContext,
            )

            // The owner's own words, already reviewed: left completely alone.
            val confirmed = all.getValue(3)
            assertEquals("Bonne séance, jambes lourdes.", confirmed.notes)
            assertNull(confirmed.detectionContext)

            // A MANUAL row is never touched, even when its own notes happen to match
            // detection's exact wording shape.
            val manual = all.getValue(4)
            assertEquals(
                "Fréquence cardiaque plus élevée que d'habitude (repos habituel ≈ 55 bpm).",
                manual.notes,
            )
            assertNull(manual.detectionContext)

            // No notes to begin with: stays that way.
            val bare = all.getValue(5)
            assertNull(bare.notes)
            assertNull(bare.detectionContext)
        } finally {
            db.close()
        }
    }

    @Test
    fun `a fresh install creates the current schema directly, no migration involved`() = runTest {
        context.deleteDatabase(dbName)
        val db = Room.databaseBuilder(context, HelionDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
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
