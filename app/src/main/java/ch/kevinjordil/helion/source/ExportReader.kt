package ch.kevinjordil.helion.source

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.store.PointSample

/**
 * Reads a Gadgetbridge export database. Opens it read-only and never writes to it:
 * the export belongs to another application.
 */
open class ExportReader {

    // open so tests can substitute a reader without a real database file
    open fun read(databasePath: String, since: Long): RawSamples {
        val db = SQLiteDatabase.openDatabase(databasePath, null, SQLiteDatabase.OPEN_READONLY)
        return db.use {
            RawSamples(
                minutes = readMinutes(it, since),
                points = readPoints(it, since),
            )
        }
    }

    // The projection MUST stay the full ExportSchema.MINUTE_COLUMNS list. Store writes
    // use OnConflictStrategy.REPLACE, which overwrites the entire row: that is only safe
    // because every write carries a complete row for that minute. A partial projection
    // here would silently null out previously-known fields on the next ingest.
    private fun readMinutes(db: SQLiteDatabase, since: Long): List<MinuteSample> =
        queryOrEmpty(
            db,
            "SELECT ${ExportSchema.MINUTE_COLUMNS.joinToString(", ")} " +
                "FROM ${ExportSchema.TABLE_MINUTE} " +
                "WHERE ${ExportSchema.COL_TIMESTAMP} > ? " +
                "ORDER BY ${ExportSchema.COL_TIMESTAMP}",
            since,
        ) { cursor ->
            MinuteSample(
                timestamp = cursor.getLong(0),
                steps = cursor.intOrNull(1),
                intensity = cursor.intOrNull(2),
                rawKind = cursor.intOrNull(3),
                heartRate = cursor.intOrNull(4)?.takeIf { it > 0 },
                sleepStage = sleepStageOf(
                    asleep = cursor.getInt(5) != 0,
                    deep = cursor.getInt(6) != 0,
                    rem = cursor.getInt(7) != 0,
                ),
            )
        }

    private fun readPoints(db: SQLiteDatabase, since: Long): List<PointSample> =
        pointSeries.flatMap { (series, table, column) ->
            queryOrEmpty(
                db,
                "SELECT ${ExportSchema.COL_TIMESTAMP}, $column FROM $table " +
                    "WHERE ${ExportSchema.COL_TIMESTAMP} > ? " +
                    "ORDER BY ${ExportSchema.COL_TIMESTAMP}",
                since,
            ) { cursor ->
                PointSample(
                    series = series,
                    timestamp = cursor.getLong(0),
                    value = cursor.getDouble(1),
                )
            }
        }

    /**
     * Runs a query, returning an empty list only if the table itself is absent.
     * Not every device reports every series, and a missing table is expected,
     * not a failure. Every other SQLite failure (a corrupt file, a disk I/O error,
     * a query that fails for some other reason) is deliberately let through: the
     * caller needs to be able to tell "nothing new" from "this read failed" so it
     * can retry without advancing its watermark, and swallowing every SQLiteException
     * here would destroy that distinction before it ever reaches the caller.
     */
    private fun <T> queryOrEmpty(
        db: SQLiteDatabase,
        sql: String,
        since: Long,
        map: (Cursor) -> T,
    ): List<T> = try {
        db.rawQuery(sql, arrayOf(since.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(map(cursor))
            }
        }
    } catch (e: SQLiteException) {
        if (e.message?.contains("no such table", ignoreCase = true) == true) {
            emptyList()
        } else {
            throw e
        }
    }

    private fun sleepStageOf(asleep: Boolean, deep: Boolean, rem: Boolean): Int = when {
        !asleep -> SleepStage.AWAKE
        deep -> SleepStage.DEEP
        rem -> SleepStage.REM
        else -> SleepStage.LIGHT
    }

    private fun Cursor.intOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)

    private data class Series(val name: String, val table: String, val column: String)

    private val pointSeries = listOf(
        Series("stress", ExportSchema.TABLE_STRESS, ExportSchema.COL_STRESS),
        Series("spo2", ExportSchema.TABLE_SPO2, ExportSchema.COL_SPO2),
        Series("pai", ExportSchema.TABLE_PAI, ExportSchema.COL_PAI_TODAY),
    ).map { Triple(it.name, it.table, it.column) }
}
