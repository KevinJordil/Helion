package ch.kevinjordil.helion.store

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Drops `sync_state.lastIngestedTimestamp`. The single global watermark it held was
 * replaced by per-series watermarks derived from the archive itself, and leaving the
 * column behind would preserve a second, wrong source of truth about how far ingestion
 * has got. SQLite before 3.35 cannot DROP COLUMN, and the app supports API 26, so the
 * table is rebuilt the portable way; the two columns worth keeping are carried over.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_state_new` (`id` INTEGER NOT NULL, " +
                "`lastSyncAttempt` INTEGER NOT NULL, `lastError` TEXT, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "INSERT INTO `sync_state_new` (`id`, `lastSyncAttempt`, `lastError`) " +
                "SELECT `id`, `lastSyncAttempt`, `lastError` FROM `sync_state`",
        )
        db.execSQL("DROP TABLE `sync_state`")
        db.execSQL("ALTER TABLE `sync_state_new` RENAME TO `sync_state`")
    }
}

/**
 * Adds the two columns backing the trigger backoff (see [SyncState]'s kdoc): a plain
 * ADD COLUMN, since both are new and nothing needs to be carried over or rebuilt.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `sync_state` ADD COLUMN `triggerFailureStreak` INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE `sync_state` ADD COLUMN `lastTriggerAttempt` INTEGER NOT NULL DEFAULT 0",
        )
    }
}

/**
 * Adds `sleep_stage_segment`, which holds the device's own hypnogram (see
 * [SleepStageSegment]) -- a brand new table, so nothing existing needs to be carried over
 * or rebuilt; every other table is left untouched.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sleep_stage_segment` (" +
                "`sessionEnd` INTEGER NOT NULL, `startTimestamp` INTEGER NOT NULL, " +
                "`endTimestamp` INTEGER NOT NULL, `stage` INTEGER NOT NULL, " +
                "PRIMARY KEY(`sessionEnd`, `startTimestamp`))",
        )
    }
}

/**
 * Adds `slot`, `activity` and `publication` -- the activity-tracking foundation (see
 * [Activity], [Slot], [Publication]). All three are brand new tables, so nothing existing
 * needs to be carried over or rebuilt; every other table is left untouched. `slot` is
 * created before `activity` (which references it) and `activity` before `publication`
 * (which references that), purely for readability -- SQLite does not require a referenced
 * table to already exist when a `FOREIGN KEY` clause is parsed.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `slot` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`label` TEXT NOT NULL, `dayOfWeek` TEXT NOT NULL, `startSecondOfDay` INTEGER NOT NULL, " +
                "`endSecondOfDay` INTEGER NOT NULL, `sport` TEXT NOT NULL, `active` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `activity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`startTimestamp` INTEGER NOT NULL, `endTimestamp` INTEGER NOT NULL, `sport` TEXT NOT NULL, " +
                "`title` TEXT, `notes` TEXT, `origin` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                "`slotId` INTEGER, FOREIGN KEY(`slotId`) REFERENCES `slot`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE SET NULL )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_activity_startTimestamp_endTimestamp` " +
                "ON `activity` (`startTimestamp`, `endTimestamp`)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_status` ON `activity` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_slotId` ON `activity` (`slotId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `publication` (`activityId` INTEGER NOT NULL, " +
                "`target` TEXT NOT NULL, `remoteId` TEXT, `state` TEXT NOT NULL, " +
                "`lastAttempt` INTEGER, `lastError` TEXT, PRIMARY KEY(`activityId`, `target`), " +
                "FOREIGN KEY(`activityId`) REFERENCES `activity`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
    }
}

/**
 * Adds `publication.uploadId` -- the Strava upload job id an in-flight ([PublicationState.UPLOADING])
 * publish attempt needs to resume from, instead of resubmitting the file, if the app is
 * killed between the submit call and the poll loop finishing (see [Publication]'s own
 * kdoc). A plain ADD COLUMN: the column is new and nullable, nothing existing is rebuilt.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `publication` ADD COLUMN `uploadId` TEXT")
    }
}

/**
 * Adds `publication.lastErrorDetail` -- Strava's own explanation text for a failed publish
 * attempt (see [Publication]'s own kdoc), which used to be thrown away entirely: every
 * failure kind collapsed to a bare reason code with no way to show what Strava actually
 * said. A plain ADD COLUMN: the column is new and nullable, nothing existing is rebuilt.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `publication` ADD COLUMN `lastErrorDetail` TEXT")
    }
}

/**
 * Adds `activity.detectionContext` (see [Activity]'s own kdoc) and, for existing rows,
 * moves detection's evidence sentence out of `notes` and into it. Before this migration,
 * a SLOT- or DETECTED-origin candidate's `notes` held exactly the rendered
 * `activity_candidate_note` sentence -- the only thing either detection pass ever wrote
 * there -- which is also what was, wrongly, forwarded as the export `description`. Any row
 * whose `notes` still has that exact shape (`origin` is SLOT or DETECTED, and the text
 * starts with "Fréquence cardiaque" and ends with "bpm)." -- the sentence's own fixed
 * wrapping) is therefore known, not guessed, to hold unedited detection text: it is moved
 * to `detectionContext` and `notes` is cleared, exactly reversing the mistake. A row that
 * does not match -- the owner replaced or appended to it after reviewing -- is left
 * completely alone: his words stay in `notes` and `detectionContext` stays null, since
 * there is no way to tell after the fact which part, if any, is still detection text, and
 * losing something he actually wrote would be worse than leaving one old row's `notes`
 * export a sentence that was already being sent before this fix shipped. No row is ever
 * deleted or merged away either way.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `activity` ADD COLUMN `detectionContext` TEXT")
        db.execSQL(
            "UPDATE `activity` SET `detectionContext` = `notes`, `notes` = NULL " +
                "WHERE `origin` IN ('SLOT', 'DETECTED') " +
                "AND `notes` LIKE 'Fréquence cardiaque%bpm).'",
        )
    }
}

/**
 * Adds `activity.notified` -- see [Activity.notified]'s own kdoc for why this flag, not a
 * side table, is what makes "one notification per candidate, ever" durable. A plain ADD
 * COLUMN with a `NOT NULL DEFAULT 0`: every existing row -- whatever its status -- is
 * treated as not yet notified, which is exactly correct for every status but
 * [ActivityStatus.CANDIDATE] (nothing reads this flag for anything else) and, for existing
 * candidates, is the safe direction to default to -- this app never notified about anything
 * before this migration, so there is nothing to avoid re-notifying, only a first batch to
 * possibly send for whatever is still sitting unreviewed.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `activity` ADD COLUMN `notified` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Adds `publication.lastMessage` -- the owner's own server's verbatim response text for a
 * *successful* send (see [Publication.lastMessage]'s own kdoc), which used to be read off
 * the connection and thrown away entirely: a repeat send and a fresh one both just said
 * "sent", with no way to see what the server actually answered. A plain ADD COLUMN: the
 * column is new and nullable, nothing existing is rebuilt.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `publication` ADD COLUMN `lastMessage` TEXT")
    }
}

/**
 * Adds `health_connect_export_state`, the single-row report
 * [ch.kevinjordil.helion.healthconnect.HealthConnectExporter] keeps of its own progress --
 * see [HealthConnectExportState]'s own kdoc. A brand-new table, nothing to carry over from
 * any existing row: every installed app is exporting to Health Connect for the first time
 * once this migration runs, whether or not the owner has since turned the feature on.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `health_connect_export_state` (" +
                "`id` INTEGER NOT NULL, " +
                "`heartRateWatermark` INTEGER NOT NULL, " +
                "`hrvWatermark` INTEGER NOT NULL, " +
                "`spo2Watermark` INTEGER NOT NULL, " +
                "`temperatureWatermark` INTEGER NOT NULL, " +
                "`respiratoryRateWatermark` INTEGER NOT NULL, " +
                "`sleepSessionWatermark` INTEGER NOT NULL, " +
                "`lastRunAttempt` INTEGER, " +
                "`lastError` TEXT, " +
                "`sleepSessionsWritten` INTEGER NOT NULL, " +
                "`exerciseSessionsWritten` INTEGER NOT NULL, " +
                "`heartRateRecordsWritten` INTEGER NOT NULL, " +
                "`stepsRecordsWritten` INTEGER NOT NULL, " +
                "`hrvRecordsWritten` INTEGER NOT NULL, " +
                "`spo2RecordsWritten` INTEGER NOT NULL, " +
                "`temperatureRecordsWritten` INTEGER NOT NULL, " +
                "`respiratoryRateRecordsWritten` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
    }
}

/**
 * Adds `sync_state.lastFullDetectionRun` -- when [ch.kevinjordil.helion.activity.ArchiveReanalyzer]
 * last ran detection over the whole archive rather than just a recent ingest window (see
 * [SyncState]'s own kdoc). A plain ADD COLUMN, nullable with no default: every existing
 * installation genuinely has never run a full re-analysis before this feature shipped, so
 * null -- not some invented timestamp -- is the correct value for every row already there.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `sync_state` ADD COLUMN `lastFullDetectionRun` INTEGER")
    }
}

/**
 * Every migration this app ships, in order, as one list rather than an argument list spelled
 * out at the call site. A migration was once defined and simply left out of that argument
 * list; Room then refused to open an upgraded database and the app died on launch with
 * nothing on screen. [ch.kevinjordil.helion.store.MigrationCoverageTest] walks this list
 * against the schema version, which only works if there is a list to walk.
 */
val HELION_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
    MIGRATION_11_12,
)
