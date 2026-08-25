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
