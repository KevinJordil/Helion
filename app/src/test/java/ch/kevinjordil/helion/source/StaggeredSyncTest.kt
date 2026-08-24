package ch.kevinjordil.helion.source

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.kevinjordil.helion.store.HelionDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Replays several ingestion passes over an export whose series arrive at different times,
 * and asserts that nothing is lost.
 *
 * The lags below are the ones measured on a real four-day export: relative to the newest
 * row anywhere in the file, PAI is stamped *ahead* of the minute stream, minute and
 * temperature trail it by a minute, stress by 25 minutes and HRV by 423 minutes -- while
 * HRV's own median sample gap is one minute, so that tail is transmission lag, not absence
 * of measurement.
 *
 * Against a single global watermark (the maximum timestamp seen anywhere, applied to every
 * table) this test fails hard: the first pass backfills everything and then pushes the
 * watermark up to the PAI row, which is ahead of every other series, so from the second
 * pass on all of HRV, most of stress, and the minutes stranded behind PAI are excluded by
 * `TIMESTAMP > since` -- permanently, because the watermark never moves back.
 */
@RunWith(RobolectricTestRunner::class)
class StaggeredSyncTest {

    private lateinit var db: HelionDatabase
    private lateinit var exportFile: File

    private class FakeSignal : ExportSignal {
        override suspend fun awaitExport(timeoutMillis: Long, trigger: () -> Unit): ExportOutcome {
            trigger()
            return ExportOutcome.Success
        }
    }

    private fun commands() = GadgetbridgeCommands(object : CommandSender {
        override fun send(intent: android.content.Intent) = Unit
    })

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HelionDatabase::class.java,
        ).allowMainThreadQueries().build()
        exportFile = File.createTempFile("staggered-export", ".db")
        exportFile.delete()
    }

    @After
    fun tearDown() {
        db.close()
        exportFile.delete()
    }

    /** Every timestamp on the one-minute grid from [ORIGIN] up to and including [limit]. */
    private fun grid(limit: Long): List<Long> =
        generateSequence(ORIGIN) { it + STEP }.takeWhile { it <= limit }.toList()

    /**
     * Rewrites the export as Gadgetbridge would at wall-clock [now]: every series carries
     * everything up to its own arrival lag, and nothing beyond it.
     */
    private fun writeExport(now: Long) {
        exportFile.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(exportFile, null)
        db.execSQL(
            "CREATE TABLE ${ExportSchema.TABLE_MINUTE} (" +
                "${ExportSchema.COL_TIMESTAMP} INTEGER, ${ExportSchema.COL_STEPS} INTEGER, " +
                "${ExportSchema.COL_RAW_INTENSITY} INTEGER, ${ExportSchema.COL_RAW_KIND} INTEGER, " +
                "${ExportSchema.COL_HEART_RATE} INTEGER, ${ExportSchema.COL_SLEEP} INTEGER)",
        )
        db.execSQL(
            "CREATE TABLE ${ExportSchema.TABLE_STRESS} (" +
                "${ExportSchema.COL_TIMESTAMP} INTEGER, ${ExportSchema.COL_STRESS} INTEGER)",
        )
        db.execSQL(
            "CREATE TABLE ${ExportSchema.TABLE_HRV} (" +
                "${ExportSchema.COL_TIMESTAMP} INTEGER, ${ExportSchema.COL_HRV_VALUE} INTEGER)",
        )
        db.execSQL(
            "CREATE TABLE ${ExportSchema.TABLE_TEMPERATURE} (" +
                "${ExportSchema.COL_TIMESTAMP} INTEGER, ${ExportSchema.COL_TEMPERATURE} REAL)",
        )
        db.execSQL(
            "CREATE TABLE ${ExportSchema.TABLE_PAI} (" +
                "${ExportSchema.COL_TIMESTAMP} INTEGER, ${ExportSchema.COL_PAI_TODAY} REAL)",
        )

        db.beginTransaction()
        try {
            grid(now - MINUTE_LAG).forEach {
                db.execSQL("INSERT INTO ${ExportSchema.TABLE_MINUTE} VALUES ($it, 3, 10, 1, 62, 0)")
            }
            grid(now - STRESS_LAG).forEach {
                db.execSQL("INSERT INTO ${ExportSchema.TABLE_STRESS} VALUES (${it * 1000}, 30)")
            }
            grid(now - HRV_LAG).forEach {
                db.execSQL("INSERT INTO ${ExportSchema.TABLE_HRV} VALUES (${it * 1000}, 45)")
            }
            grid(now - TEMPERATURE_LAG).forEach {
                db.execSQL("INSERT INTO ${ExportSchema.TABLE_TEMPERATURE} VALUES (${it * 1000}, 36.5)")
            }
            // PAI is written once per pass and stamped ahead of everything else.
            db.execSQL("INSERT INTO ${ExportSchema.TABLE_PAI} VALUES (${(now + PAI_LEAD) * 1000}, 1.5)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    private fun exportedTimestamps(table: String, unitDivisor: Long): List<Long> {
        val db = SQLiteDatabase.openDatabase(exportFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return db.use {
            it.rawQuery(
                "SELECT ${ExportSchema.COL_TIMESTAMP} FROM $table ORDER BY ${ExportSchema.COL_TIMESTAMP}",
                null,
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getLong(0) / unitDivisor) }
            }
        }
    }

    @Test
    fun `staggered series survive repeated passes`() = runTest {
        val ingestor = Ingestor(ExportReader(), commands(), FakeSignal(), db) { 0 }

        // PAI accumulates across passes; every other series is rewritten in full each time.
        val paiSeen = mutableListOf<Long>()
        repeat(PASSES) { pass ->
            val now = FIRST_PASS_AT + pass * PASS_INTERVAL
            writeExport(now)
            paiSeen += now + PAI_LEAD
            assertTrue(ingestor.ingest(exportFile.absolutePath) is IngestResult.Ingested)
        }

        val storedMinutes = db.minuteSamples().between(0, Long.MAX_VALUE).map { it.timestamp }
        assertEquals(exportedTimestamps(ExportSchema.TABLE_MINUTE, 1), storedMinutes)

        assertEquals(
            exportedTimestamps(ExportSchema.TABLE_HRV, 1000),
            db.pointSamples().between("hrv", 0, Long.MAX_VALUE).map { it.timestamp },
        )
        assertEquals(
            exportedTimestamps(ExportSchema.TABLE_STRESS, 1000),
            db.pointSamples().between("stress", 0, Long.MAX_VALUE).map { it.timestamp },
        )
        assertEquals(
            exportedTimestamps(ExportSchema.TABLE_TEMPERATURE, 1000),
            db.pointSamples().between("temperature", 0, Long.MAX_VALUE).map { it.timestamp },
        )
        // Each pass's PAI row was only ever present in that pass's export, so the archive
        // is the only place the earlier ones still exist.
        assertEquals(paiSeen, db.pointSamples().between("pai", 0, Long.MAX_VALUE).map { it.timestamp })
    }

    private companion object {
        const val STEP = 60L
        const val ORIGIN = 1_700_000_000L
        const val MINUTE_LAG = 60L
        const val TEMPERATURE_LAG = 60L
        const val STRESS_LAG = 25 * 60L
        const val HRV_LAG = 423 * 60L
        const val PAI_LEAD = 60L

        /** Far enough past [ORIGIN] that even the slowest series has something to report. */
        const val FIRST_PASS_AT = ORIGIN + 30_000L
        const val PASS_INTERVAL = 1_800L
        const val PASSES = 4
    }
}
