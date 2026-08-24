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
    //
    // The minute table's TIMESTAMP is Unix seconds already, unlike the point tables below.
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
                sleepStage = sleepStageOf(sleep = cursor.getInt(5)),
            )
        }

    // Every point table has its own TimeUnit (see below): most of them store TIMESTAMP in
    // Unix milliseconds, unlike the minute table above and unlike the rest of the app
    // (including `since`), which work in Unix seconds throughout. That mismatch is
    // normalised right here at the reader boundary, per series: the watermark is
    // converted into the table's native unit to filter correctly, and every returned
    // timestamp is converted back to seconds. Nothing past this point may ever see a
    // millisecond value -- a single one poisons the ingestor's watermark and freezes sync.
    private fun readPoints(db: SQLiteDatabase, since: Long): List<PointSample> =
        pointSeries.flatMap { series ->
            queryOrEmpty(
                db,
                "SELECT ${ExportSchema.COL_TIMESTAMP}, ${series.column} FROM ${series.table} " +
                    "WHERE ${ExportSchema.COL_TIMESTAMP} > ? " +
                    "ORDER BY ${ExportSchema.COL_TIMESTAMP}",
                series.unit.toExportUnits(since),
            ) { cursor ->
                PointSample(
                    series = series.name,
                    timestamp = series.unit.toSeconds(cursor.getLong(0)),
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

    /**
     * This device does not expose per-minute sleep stages. In a real export, SLEEP is
     * observed to take values in {0, 64, 128, 192, 255}, while DEEP_SLEEP and REM_SLEEP
     * only ever take values in {0, 128} -- and whenever SLEEP is non-zero, DEEP_SLEEP and
     * REM_SLEEP are BOTH 128, constantly (over 2000 asleep minutes were byte-identical on
     * those two columns). RAW_KIND does not differentiate stages either. Treating those
     * two columns as real stage flags, as an earlier version of this reader did, produced
     * a chart that was almost entirely "deep sleep" -- plausible-looking and completely
     * wrong. So only the coarse AWAKE/ASLEEP distinction is derived here; DEEP_SLEEP and
     * REM_SLEEP are not read at all. Do not resurrect finer stages from this table.
     */
    private fun sleepStageOf(sleep: Int): Int =
        if (sleep == 0) SleepStage.AWAKE else SleepStage.ASLEEP

    private fun Cursor.intOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)

    /** The unit a point table's TIMESTAMP column is stored in on the device export. */
    private enum class TimeUnit {
        SECONDS {
            override fun toExportUnits(sinceSeconds: Long) = sinceSeconds
            override fun toSeconds(raw: Long) = raw
        },
        MILLISECONDS {
            override fun toExportUnits(sinceSeconds: Long) = sinceSeconds * 1000
            override fun toSeconds(raw: Long) = raw / 1000
        },
        ;

        /** Converts a seconds-based watermark into this table's native unit for querying. */
        abstract fun toExportUnits(sinceSeconds: Long): Long

        /** Converts a raw value read from this table into Unix seconds. */
        abstract fun toSeconds(raw: Long): Long
    }

    private data class Series(val name: String, val table: String, val column: String, val unit: TimeUnit)

    private val pointSeries = listOf(
        Series("stress", ExportSchema.TABLE_STRESS, ExportSchema.COL_STRESS, TimeUnit.MILLISECONDS),
        Series("spo2", ExportSchema.TABLE_SPO2, ExportSchema.COL_SPO2, TimeUnit.MILLISECONDS),
        Series("pai", ExportSchema.TABLE_PAI, ExportSchema.COL_PAI_TODAY, TimeUnit.MILLISECONDS),
        Series("hrv", ExportSchema.TABLE_HRV, ExportSchema.COL_HRV_VALUE, TimeUnit.MILLISECONDS),
        Series("temperature", ExportSchema.TABLE_TEMPERATURE, ExportSchema.COL_TEMPERATURE, TimeUnit.MILLISECONDS),
    )
}
