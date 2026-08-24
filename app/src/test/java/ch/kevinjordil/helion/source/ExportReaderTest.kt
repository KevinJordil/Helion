package ch.kevinjordil.helion.source

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ExportReaderTest {

    private fun buildExport(withStress: Boolean = true): String {
        val file = File.createTempFile("export", ".db")
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            "CREATE TABLE ${ExportSchema.TABLE_MINUTE} (" +
                "${ExportSchema.COL_TIMESTAMP} INTEGER, ${ExportSchema.COL_STEPS} INTEGER, " +
                "${ExportSchema.COL_RAW_INTENSITY} INTEGER, ${ExportSchema.COL_RAW_KIND} INTEGER, " +
                "${ExportSchema.COL_HEART_RATE} INTEGER, ${ExportSchema.COL_SLEEP} INTEGER, " +
                "${ExportSchema.COL_DEEP_SLEEP} INTEGER, ${ExportSchema.COL_REM_SLEEP} INTEGER)",
        )
        db.execSQL(
            "INSERT INTO ${ExportSchema.TABLE_MINUTE} VALUES " +
                "(1700000000, 10, 20, 1, 60, 0, 0, 0), " +
                "(1700000060, 0, 0, 8, 55, 1, 1, 0), " +
                "(1700000120, 5, 15, 1, 70, 0, 0, 0)",
        )
        if (withStress) {
            db.execSQL(
                "CREATE TABLE ${ExportSchema.TABLE_STRESS} (" +
                    "${ExportSchema.COL_TIMESTAMP} INTEGER, ${ExportSchema.COL_STRESS} INTEGER)",
            )
            db.execSQL("INSERT INTO ${ExportSchema.TABLE_STRESS} VALUES (1700000060, 42)")
        }
        db.close()
        return file.absolutePath
    }

    /**
     * A minute table missing the HEART_RATE column: the reader's projection still asks
     * for it, so the query fails with a genuine SQLite error ("no such column"), not with
     * a missing table. Used to prove that such a failure propagates instead of being
     * swallowed as an empty result.
     */
    private fun buildExportWithMalformedMinuteTable(): String {
        val file = File.createTempFile("export", ".db")
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            "CREATE TABLE ${ExportSchema.TABLE_MINUTE} (" +
                "${ExportSchema.COL_TIMESTAMP} INTEGER, ${ExportSchema.COL_STEPS} INTEGER, " +
                "${ExportSchema.COL_RAW_INTENSITY} INTEGER, ${ExportSchema.COL_RAW_KIND} INTEGER, " +
                "${ExportSchema.COL_SLEEP} INTEGER, " +
                "${ExportSchema.COL_DEEP_SLEEP} INTEGER, ${ExportSchema.COL_REM_SLEEP} INTEGER)",
        )
        db.execSQL(
            "INSERT INTO ${ExportSchema.TABLE_MINUTE} VALUES (1700000000, 10, 20, 1, 0, 0, 0)",
        )
        db.close()
        return file.absolutePath
    }

    private fun buildExportWithHeartRates(rows: List<Pair<Long, String>>): String {
        val file = File.createTempFile("export", ".db")
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            "CREATE TABLE ${ExportSchema.TABLE_MINUTE} (" +
                "${ExportSchema.COL_TIMESTAMP} INTEGER, ${ExportSchema.COL_STEPS} INTEGER, " +
                "${ExportSchema.COL_RAW_INTENSITY} INTEGER, ${ExportSchema.COL_RAW_KIND} INTEGER, " +
                "${ExportSchema.COL_HEART_RATE} INTEGER, ${ExportSchema.COL_SLEEP} INTEGER, " +
                "${ExportSchema.COL_DEEP_SLEEP} INTEGER, ${ExportSchema.COL_REM_SLEEP} INTEGER)",
        )
        rows.forEach { (timestamp, heartRateLiteral) ->
            db.execSQL(
                "INSERT INTO ${ExportSchema.TABLE_MINUTE} VALUES " +
                    "($timestamp, 0, 0, 1, $heartRateLiteral, 0, 0, 0)",
            )
        }
        db.close()
        return file.absolutePath
    }

    @Test
    fun `reads every minute when starting from scratch`() {
        val result = ExportReader().read(buildExport(), since = 0)
        assertEquals(3, result.minutes.size)
        assertEquals(1700000000, result.minutes.first().timestamp)
        assertEquals(60, result.minutes.first().heartRate)
    }

    @Test
    fun `reads only what is newer than the watermark`() {
        val result = ExportReader().read(buildExport(), since = 1700000000)
        assertEquals(2, result.minutes.size)
        assertTrue(result.minutes.all { it.timestamp > 1700000000 })
    }

    @Test
    fun `maps sleep flags to a single stage`() {
        val result = ExportReader().read(buildExport(), since = 0)
        val asleep = result.minutes.single { it.timestamp == 1700000060L }
        assertEquals(SleepStage.DEEP, asleep.sleepStage)
        val awake = result.minutes.single { it.timestamp == 1700000000L }
        assertEquals(SleepStage.AWAKE, awake.sleepStage)
    }

    @Test
    fun `reads point series into the shared table`() {
        val result = ExportReader().read(buildExport(), since = 0)
        val stress = result.points.single { it.series == "stress" }
        assertEquals(42.0, stress.value, 0.0)
    }

    @Test
    fun `a missing table is not an error`() {
        val result = ExportReader().read(buildExport(withStress = false), since = 0)
        assertEquals(3, result.minutes.size)
        assertTrue(result.points.isEmpty())
    }

    @Test
    fun `every field of a minute row is mapped to its own distinct value`() {
        // Columns are read by cursor index (0..7); a swap of two adjacent
        // columns would map values to the wrong fields. Every value below is
        // distinct so such a swap cannot coincidentally pass this test.
        val result = ExportReader().read(buildExport(), since = 0)
        val row = result.minutes.single { it.timestamp == 1700000120L }
        assertEquals(1700000120L, row.timestamp)
        assertEquals(5, row.steps)
        assertEquals(15, row.intensity)
        assertEquals(1, row.rawKind)
        assertEquals(70, row.heartRate)
        assertEquals(SleepStage.AWAKE, row.sleepStage)
    }

    @Test
    fun `a genuine query failure propagates instead of being swallowed`() {
        val reader = ExportReader()
        assertThrows(SQLiteException::class.java) {
            reader.read(buildExportWithMalformedMinuteTable(), since = 0)
        }
    }

    @Test
    fun `heart rate is null for both encodings of absent and preserved when valid`() {
        val path = buildExportWithHeartRates(
            listOf(
                1700000000L to "NULL",
                1700000060L to "0",
                1700000120L to "-1",
                1700000180L to "60",
            ),
        )
        val result = ExportReader().read(path, since = 0)
        assertEquals(null, result.minutes.single { it.timestamp == 1700000000L }.heartRate)
        assertEquals(null, result.minutes.single { it.timestamp == 1700000060L }.heartRate)
        assertEquals(null, result.minutes.single { it.timestamp == 1700000120L }.heartRate)
        assertEquals(60, result.minutes.single { it.timestamp == 1700000180L }.heartRate)
    }
}
