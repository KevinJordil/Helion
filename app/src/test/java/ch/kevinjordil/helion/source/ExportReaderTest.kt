package ch.kevinjordil.helion.source

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

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


    /** Builds an export carrying only the sleep session table, one row per (TIMESTAMP millis, blob) pair. */
    private fun buildExportWithSessions(rows: List<Pair<Long, ByteArray>>): String {
        val file = File.createTempFile("export", ".db")
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            "CREATE TABLE ${ExportSchema.TABLE_SLEEP_SESSION} (" +
                "${ExportSchema.COL_TIMESTAMP} INTEGER, ${ExportSchema.COL_DATA} BLOB)",
        )
        rows.forEach { (timestampMillis, data) ->
            val values = ContentValues()
            values.put(ExportSchema.COL_TIMESTAMP, timestampMillis)
            values.put(ExportSchema.COL_DATA, data)
            db.insert(ExportSchema.TABLE_SLEEP_SESSION, null, values)
        }
        db.close()
        return file.absolutePath
    }

    /**
     * Builds a session blob in the real layout: an 8-byte header (session-end seconds,
     * midnight-of-day-ends seconds), a zero-padding run, the segment count and array, more
     * padding, then the 8-byte footer of totals -- the same shape verified against a real
     * export (see [ch.kevinjordil.helion.source.SleepSessionBlobTest] for the decoder's own
     * fixtures; this one exercises the reader's header handling on top of that).
     */
    private fun buildSessionBlob(
        sessionEndSeconds: Long,
        dayEndsMidnightSeconds: Long,
        segments: List<Triple<Int, Int, Int>>,
        totals: IntArray, // rem, light, deep, awake
    ): ByteArray {
        val paddingBeforeCount = 20
        val trailingPadding = 40
        val headerAndPadding = 8 + paddingBeforeCount
        val size = headerAndPadding + 2 + 5 * segments.size + trailingPadding + 8
        val data = ByteArray(size)
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, sessionEndSeconds.toInt())
        buffer.putInt(4, dayEndsMidnightSeconds.toInt())

        var offset = headerAndPadding
        buffer.putShort(offset, segments.size.toShort())
        offset += 2
        segments.forEach { (start, end, type) ->
            buffer.putShort(offset, start.toShort())
            buffer.putShort(offset + 2, end.toShort())
            data[offset + 4] = type.toByte()
            offset += 5
        }

        val footerStart = size - 8
        totals.forEachIndexed { index, value -> buffer.putShort(footerStart + index * 2, value.toShort()) }
        return data
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
    fun `heart rate keeps valid readings and drops every absent encoding including 255`() {
        // 255 is Huami's "not measured" sentinel and it does occur in real exports; it is
        // not a 255 bpm heart rate. Gadgetbridge treats anything outside 10-224 as invalid
        // and so does this reader, so the sentinel never reaches the archive.
        val path = buildExportWithHeartRates(
            listOf(
                1700000000L to "NULL",
                1700000060L to "0",
                1700000120L to "-1",
                1700000180L to "60",
                1700000240L to "255",
                1700000300L to "225",
                1700000360L to "9",
                1700000420L to "224",
                1700000480L to "10",
            ),
        )
        val result = ExportReader().read(path, Watermarks.NONE)
        fun heartRateAt(timestamp: Long) = result.minutes.single { it.timestamp == timestamp }.heartRate
        assertEquals(null, heartRateAt(1700000000L))
        assertEquals(null, heartRateAt(1700000060L))
        assertEquals(null, heartRateAt(1700000120L))
        assertEquals(60, heartRateAt(1700000180L))
        assertEquals(null, heartRateAt(1700000240L))
        assertEquals(null, heartRateAt(1700000300L))
        assertEquals(null, heartRateAt(1700000360L))
        assertEquals(224, heartRateAt(1700000420L))
        assertEquals(10, heartRateAt(1700000480L))
    }

    @Test
    fun `the minute row survives a sentinel heart rate, only the reading is dropped`() {
        // Dropping the whole row would lose that minute's steps and sleep as well, and
        // would stall the minute watermark on a run of unmeasured minutes.
        val path = buildExportWithHeartRates(listOf(1700000000L to "255"))
        val row = ExportReader().read(path, Watermarks.NONE).minutes.single()
        assertEquals(1700000000L, row.timestamp)
        assertEquals(null, row.heartRate)
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

    @Test
    fun `a well-formed session blob decodes into absolute-timestamp stage segments`() {
        val dayStartedMidnight = 2_000_000_000L
        val dayEndsMidnight = dayStartedMidnight + 86_400
        val sessionEndSeconds = dayStartedMidnight + 200 * 60
        val blob = buildSessionBlob(
            sessionEndSeconds = sessionEndSeconds,
            dayEndsMidnightSeconds = dayEndsMidnight,
            segments = listOf(
                Triple(100, 129, DeviceSleepStage.LIGHT),
                Triple(130, 149, DeviceSleepStage.DEEP),
            ),
            totals = intArrayOf(0, 30, 20, 0),
        )
        val path = buildExportWithSessions(listOf(sessionEndSeconds * 1000 to blob))

        val result = ExportReader().read(path, Watermarks.NONE)
        assertEquals(2, result.stageSegments.size)
        val light = result.stageSegments.single { it.stage == DeviceSleepStage.LIGHT }
        assertEquals(dayStartedMidnight + 100 * 60, light.startTimestamp)
        assertEquals(dayStartedMidnight + 129 * 60, light.endTimestamp)
        assertEquals(sessionEndSeconds, light.sessionEnd)
        val deep = result.stageSegments.single { it.stage == DeviceSleepStage.DEEP }
        assertEquals(dayStartedMidnight + 130 * 60, deep.startTimestamp)
        assertEquals(dayStartedMidnight + 149 * 60, deep.endTimestamp)
    }

    @Test
    fun `sessions are filtered by their own watermark, in the table's millisecond unit`() {
        val dayStartedMidnight = 2_000_000_000L
        val dayEndsMidnight = dayStartedMidnight + 86_400
        val olderEnd = dayStartedMidnight + 200 * 60
        val newerEnd = dayStartedMidnight + 300 * 60
        fun blobFor(end: Long) = buildSessionBlob(
            sessionEndSeconds = end,
            dayEndsMidnightSeconds = dayEndsMidnight,
            segments = listOf(Triple(0, 29, DeviceSleepStage.LIGHT)),
            totals = intArrayOf(0, 30, 0, 0),
        )
        val path = buildExportWithSessions(
            listOf(
                olderEnd * 1000 to blobFor(olderEnd),
                newerEnd * 1000 to blobFor(newerEnd),
            ),
        )

        val result = ExportReader().read(path, Watermarks(sessions = olderEnd))
        assertTrue(result.stageSegments.all { it.sessionEnd == newerEnd })
        assertEquals(1, result.stageSegments.map { it.sessionEnd }.toSet().size)
    }

    @Test
    fun `an unparseable session blob contributes no segments and does not fail the read`() {
        // Totals disagree with the (single) segment's own duration -- fails validation.
        val dayStartedMidnight = 2_000_000_000L
        val dayEndsMidnight = dayStartedMidnight + 86_400
        val sessionEnd = dayStartedMidnight + 200 * 60
        val blob = buildSessionBlob(
            sessionEndSeconds = sessionEnd,
            dayEndsMidnightSeconds = dayEndsMidnight,
            segments = listOf(Triple(0, 29, DeviceSleepStage.LIGHT)), // 30 real minutes
            totals = intArrayOf(0, 999, 0, 0), // wrong on purpose
        )
        val path = buildExportWithSessions(listOf(sessionEnd * 1000 to blob))

        val result = ExportReader().read(path, Watermarks.NONE)
        assertTrue(result.stageSegments.isEmpty())
    }
}
