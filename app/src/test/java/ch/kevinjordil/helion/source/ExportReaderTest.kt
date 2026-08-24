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
                "${ExportSchema.COL_HEART_RATE} INTEGER, ${ExportSchema.COL_SLEEP} INTEGER)",
        )
        db.execSQL(
            "INSERT INTO ${ExportSchema.TABLE_MINUTE} VALUES " +
                "(1700000000, 10, 20, 1, 60, 0), " +
                "(1700000060, 0, 0, 8, 55, 192), " +
                "(1700000120, 5, 15, 1, 70, 0)",
        )
        if (withStress) {
            // Point tables store TIMESTAMP in Unix milliseconds; the minute table above
            // does not. 1700000060 seconds -> 1700000060000 milliseconds.
            db.execSQL(
                "CREATE TABLE ${ExportSchema.TABLE_STRESS} (" +
                    "${ExportSchema.COL_TIMESTAMP} INTEGER, ${ExportSchema.COL_STRESS} INTEGER)",
            )
            db.execSQL("INSERT INTO ${ExportSchema.TABLE_STRESS} VALUES (1700000060000, 42)")
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
                "${ExportSchema.COL_SLEEP} INTEGER)",
        )
        db.execSQL(
            "INSERT INTO ${ExportSchema.TABLE_MINUTE} VALUES (1700000000, 10, 20, 1, 0)",
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
                "${ExportSchema.COL_HEART_RATE} INTEGER, ${ExportSchema.COL_SLEEP} INTEGER)",
        )
        rows.forEach { (timestamp, heartRateLiteral) ->
            db.execSQL(
                "INSERT INTO ${ExportSchema.TABLE_MINUTE} VALUES " +
                    "($timestamp, 0, 0, 1, $heartRateLiteral, 0)",
            )
        }
        db.close()
        return file.absolutePath
    }

    /**
     * Real observed encoding: SLEEP takes values in {0, 64, 128, 192, 255}, and whenever
     * it is non-zero, DEEP_SLEEP and REM_SLEEP are both 128 -- but those two columns are no
     * longer part of the schema at all (see ExportReader.sleepStageOf), so this fixture
     * only carries SLEEP.
     */
    private fun buildExportWithSleepValues(sleepValues: List<Pair<Long, Int>>): String {
        val file = File.createTempFile("export", ".db")
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            "CREATE TABLE ${ExportSchema.TABLE_MINUTE} (" +
                "${ExportSchema.COL_TIMESTAMP} INTEGER, ${ExportSchema.COL_STEPS} INTEGER, " +
                "${ExportSchema.COL_RAW_INTENSITY} INTEGER, ${ExportSchema.COL_RAW_KIND} INTEGER, " +
                "${ExportSchema.COL_HEART_RATE} INTEGER, ${ExportSchema.COL_SLEEP} INTEGER)",
        )
        sleepValues.forEach { (timestamp, sleep) ->
            db.execSQL(
                "INSERT INTO ${ExportSchema.TABLE_MINUTE} VALUES ($timestamp, 0, 0, 1, 0, $sleep)",
            )
        }
        db.close()
        return file.absolutePath
    }

    /** Builds an export with only the point tables needed for millisecond-unit tests. */
    private fun buildExportWithMillisecondPoints(
        hrvRows: List<Pair<Long, Int>> = emptyList(),
        temperatureRows: List<Pair<Long, Double>> = emptyList(),
    ): String {
        val file = File.createTempFile("export", ".db")
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            "CREATE TABLE ${ExportSchema.TABLE_MINUTE} (" +
                "${ExportSchema.COL_TIMESTAMP} INTEGER, ${ExportSchema.COL_STEPS} INTEGER, " +
                "${ExportSchema.COL_RAW_INTENSITY} INTEGER, ${ExportSchema.COL_RAW_KIND} INTEGER, " +
                "${ExportSchema.COL_HEART_RATE} INTEGER, ${ExportSchema.COL_SLEEP} INTEGER)",
        )
        db.execSQL(
            "CREATE TABLE ${ExportSchema.TABLE_HRV} (" +
                "${ExportSchema.COL_TIMESTAMP} INTEGER, ${ExportSchema.COL_HRV_VALUE} INTEGER)",
        )
        hrvRows.forEach { (timestampMillis, value) ->
            db.execSQL("INSERT INTO ${ExportSchema.TABLE_HRV} VALUES ($timestampMillis, $value)")
        }
        db.execSQL(
            "CREATE TABLE ${ExportSchema.TABLE_TEMPERATURE} (" +
                "${ExportSchema.COL_TIMESTAMP} INTEGER, ${ExportSchema.COL_TEMPERATURE} REAL, " +
                "TEMPERATURE_TYPE INTEGER, TEMPERATURE_LOCATION INTEGER)",
        )
        temperatureRows.forEach { (timestampMillis, value) ->
            db.execSQL(
                "INSERT INTO ${ExportSchema.TABLE_TEMPERATURE} VALUES " +
                    "($timestampMillis, $value, 0, 0)",
            )
        }
        db.close()
        return file.absolutePath
    }

    @Test
    fun `reads every minute when starting from scratch`() {
        val result = ExportReader().read(buildExport(), Watermarks.NONE)
        assertEquals(3, result.minutes.size)
        assertEquals(1700000000, result.minutes.first().timestamp)
        assertEquals(60, result.minutes.first().heartRate)
    }

    @Test
    fun `reads only what is newer than the watermark`() {
        val result = ExportReader().read(buildExport(), Watermarks(minutes = 1700000000))
        assertEquals(2, result.minutes.size)
        assertTrue(result.minutes.all { it.timestamp > 1700000000 })
    }

    @Test
    fun `maps sleep flag to a single stage`() {
        val result = ExportReader().read(buildExport(), Watermarks.NONE)
        val asleep = result.minutes.single { it.timestamp == 1700000060L }
        assertEquals(SleepStage.ASLEEP, asleep.sleepStage)
        val awake = result.minutes.single { it.timestamp == 1700000000L }
        assertEquals(SleepStage.AWAKE, awake.sleepStage)
    }

    @Test
    fun `only AWAKE and ASLEEP are ever produced across the real observed SLEEP encoding`() {
        // Observed on a real export: SLEEP in {0, 64, 128, 192, 255}, with DEEP_SLEEP and
        // REM_SLEEP both constantly 128 whenever SLEEP is non-zero -- meaning the device
        // exposes no real per-minute stage information. Only two stages must ever appear.
        val path = buildExportWithSleepValues(
            listOf(
                1700000000L to 0,
                1700000060L to 64,
                1700000120L to 128,
                1700000180L to 192,
                1700000240L to 255,
            ),
        )
        val result = ExportReader().read(path, Watermarks.NONE)
        assertEquals(SleepStage.AWAKE, result.minutes.single { it.timestamp == 1700000000L }.sleepStage)
        assertEquals(SleepStage.ASLEEP, result.minutes.single { it.timestamp == 1700000060L }.sleepStage)
        assertEquals(SleepStage.ASLEEP, result.minutes.single { it.timestamp == 1700000120L }.sleepStage)
        assertEquals(SleepStage.ASLEEP, result.minutes.single { it.timestamp == 1700000180L }.sleepStage)
        assertEquals(SleepStage.ASLEEP, result.minutes.single { it.timestamp == 1700000240L }.sleepStage)
        val stages = result.minutes.map { it.sleepStage }.toSet()
        assertEquals(setOf(SleepStage.AWAKE, SleepStage.ASLEEP), stages)
    }

    @Test
    fun `reads point series into the shared table`() {
        val result = ExportReader().read(buildExport(), Watermarks.NONE)
        val stress = result.points.single { it.series == "stress" }
        assertEquals(42.0, stress.value, 0.0)
    }

    @Test
    fun `a millisecond-stamped point row surfaces with a second-based timestamp`() {
        // The export stores 1700000060000 milliseconds; the reader must hand back
        // 1700000060 seconds, matching the unit the rest of the app uses everywhere.
        val result = ExportReader().read(buildExport(), Watermarks.NONE)
        val stress = result.points.single { it.series == "stress" }
        assertEquals(1700000060L, stress.timestamp)
    }

    @Test
    fun `since filtering is correct across the millisecond unit boundary`() {
        // One point row one second before the watermark, one row one second after it,
        // both expressed as milliseconds in the export. Only the later one must survive.
        val since = 1700000060L
        val path = buildExportWithMillisecondPoints(
            hrvRows = listOf(
                (since - 1) * 1000 to 55,
                (since + 1) * 1000 to 60,
            ),
        )
        val result = ExportReader().read(path, Watermarks(points = mapOf("hrv" to since)))
        val hrv = result.points.filter { it.series == "hrv" }
        assertEquals(1, hrv.size)
        assertEquals(since + 1, hrv.single().timestamp)
        assertEquals(60.0, hrv.single().value, 0.0)
    }

    @Test
    fun `reads hrv and temperature series`() {
        val path = buildExportWithMillisecondPoints(
            hrvRows = listOf(1700000060000L to 45),
            temperatureRows = listOf(1700000060000L to 36.6),
        )
        val result = ExportReader().read(path, Watermarks.NONE)
        val hrv = result.points.single { it.series == "hrv" }
        assertEquals(1700000060L, hrv.timestamp)
        assertEquals(45.0, hrv.value, 0.0)
        val temperature = result.points.single { it.series == "temperature" }
        assertEquals(1700000060L, temperature.timestamp)
        assertEquals(36.6, temperature.value, 0.0)
    }

    @Test
    fun `low temperature readings are not filtered out`() {
        // The reader's contract is raw fidelity; a low reading (e.g. strap not worn) is
        // not this layer's business to interpret away.
        val path = buildExportWithMillisecondPoints(
            temperatureRows = listOf(1700000060000L to 12.3),
        )
        val result = ExportReader().read(path, Watermarks.NONE)
        val temperature = result.points.single { it.series == "temperature" }
        assertEquals(12.3, temperature.value, 0.0)
    }

    @Test
    fun `a missing table is not an error`() {
        val result = ExportReader().read(buildExport(withStress = false), Watermarks.NONE)
        assertEquals(3, result.minutes.size)
        assertTrue(result.points.isEmpty())
    }

    @Test
    fun `every field of a minute row is mapped to its own distinct value`() {
        // Columns are read by cursor index (0..5); a swap of two adjacent
        // columns would map values to the wrong fields. Every value below is
        // distinct so such a swap cannot coincidentally pass this test.
        val result = ExportReader().read(buildExport(), Watermarks.NONE)
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
            reader.read(buildExportWithMalformedMinuteTable(), Watermarks.NONE)
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
        val result = ExportReader().read(path, Watermarks.NONE)
        assertEquals(null, result.minutes.single { it.timestamp == 1700000000L }.heartRate)
        assertEquals(null, result.minutes.single { it.timestamp == 1700000060L }.heartRate)
        assertEquals(null, result.minutes.single { it.timestamp == 1700000120L }.heartRate)
        assertEquals(60, result.minutes.single { it.timestamp == 1700000180L }.heartRate)
    }

    @Test
    fun `each series is filtered by its own watermark, not by a shared one`() {
        // The failure this guards against: one global watermark set by the freshest series
        // excludes every series that arrives later.
        val path = buildExportWithMillisecondPoints(
            hrvRows = listOf(1700000060000L to 40, 1700000120000L to 41),
            temperatureRows = listOf(1700000060000L to 36.0, 1700000120000L to 36.5),
        )
        val result = ExportReader().read(
            path,
            Watermarks(points = mapOf("hrv" to 1700000060L)),
        )
        assertEquals(listOf(1700000120L), result.points.filter { it.series == "hrv" }.map { it.timestamp })
        // temperature has no watermark of its own here, so it backfills in full.
        assertEquals(
            listOf(1700000060L, 1700000120L),
            result.points.filter { it.series == "temperature" }.map { it.timestamp },
        )
    }

    @Test
    fun `a truncated export surfaces as a failure, not as a silent empty read`() {
        // A copy interrupted halfway leaves a file that is not a database at all. Reading
        // it must throw, so the pass fails and retries, rather than reporting "nothing new"
        // and leaving the owner looking at stale data with a healthy-looking indicator.
        val complete = File(buildExport())
        val truncated = File.createTempFile("truncated", ".db")
        truncated.writeBytes(complete.readBytes().copyOf(complete.length().toInt() / 3))

        assertThrows(SQLiteException::class.java) {
            ExportReader().read(truncated.absolutePath, Watermarks.NONE)
        }
    }

    @Test
    fun `a file that is not a database at all surfaces as a failure`() {
        val garbage = File.createTempFile("garbage", ".db")
        garbage.writeBytes(ByteArray(4096) { 0x7A })

        assertThrows(SQLiteException::class.java) {
            ExportReader().read(garbage.absolutePath, Watermarks.NONE)
        }
    }

    @Test
    fun `the published series names match what the reader actually emits`() {
        val path = buildExport()
        val emitted = ExportReader().read(path, Watermarks.NONE).points.map { it.series }.toSet()
        assertTrue(ExportReader.POINT_SERIES_NAMES.containsAll(emitted))
        assertEquals(ExportReader.POINT_SERIES_NAMES.size, ExportReader.POINT_SERIES_NAMES.toSet().size)
    }
}
