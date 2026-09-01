package ch.kevinjordil.helion.export

import ch.kevinjordil.helion.store.SportType
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [tcxDownloadFileName] and [uniqueDownloadFileName] are the whole of what makes the save
 * action's confirmation ("Enregistré dans Téléchargements : <name>") meaningful and safe:
 * a name built only from the sport and the start time, using only characters safe on any
 * filesystem, and never silently colliding when two activities start in the same minute.
 */
class DownloadsExportTest {

    private val zone = ZoneOffset.UTC

    // 2026-08-26 20:10:00 UTC
    private val start = 1787775000L

    @Test
    fun `the file name is built from the sport slug and the start time, down to the minute`() {
        assertEquals("badminton-2026-08-26-2010.tcx", tcxDownloadFileName(SportType.BADMINTON, start, zone))
        assertEquals("run-2026-08-26-2010.tcx", tcxDownloadFileName(SportType.RUN, start, zone))
        assertEquals("ride-2026-08-26-2010.tcx", tcxDownloadFileName(SportType.RIDE, start, zone))
        assertEquals("walk-2026-08-26-2010.tcx", tcxDownloadFileName(SportType.WALK, start, zone))
        assertEquals("swim-2026-08-26-2010.tcx", tcxDownloadFileName(SportType.SWIM, start, zone))
        assertEquals("rock-climbing-2026-08-26-2010.tcx", tcxDownloadFileName(SportType.ROCK_CLIMBING, start, zone))
        assertEquals("workout-2026-08-26-2010.tcx", tcxDownloadFileName(SportType.WORKOUT, start, zone))
        assertEquals(
            "high-intensity-interval-training-2026-08-26-2010.tcx",
            tcxDownloadFileName(SportType.HIGH_INTENSITY_INTERVAL_TRAINING, start, zone),
        )
    }

    @Test
    fun `the file name only ever contains lower-case letters, digits, hyphens and one dot`() {
        SportType.entries.forEach { sport ->
            val name = tcxDownloadFileName(sport, start, zone)
            assertTrue("\"$name\" contains an unsafe character", name.matches(Regex("[a-z0-9-]+\\.tcx")))
        }
    }

    @Test
    fun `two activities of the same sport starting in the same minute produce the same base name`() {
        val sameMinuteLater = start + 30 // 30 seconds later, same minute
        assertEquals(
            tcxDownloadFileName(SportType.BADMINTON, start, zone),
            tcxDownloadFileName(SportType.BADMINTON, sameMinuteLater, zone),
        )
    }

    @Test
    fun `two activities starting a minute apart get different base names`() {
        val nextMinute = start + 60
        assertNotEquals(
            tcxDownloadFileName(SportType.BADMINTON, start, zone),
            tcxDownloadFileName(SportType.BADMINTON, nextMinute, zone),
        )
    }

    @Test
    fun `a colliding base name is resolved with the smallest free numeric suffix`() {
        val base = "badminton-2026-08-26-2010.tcx"
        assertEquals(base, uniqueDownloadFileName(base, existingNames = emptySet()))
        assertEquals("badminton-2026-08-26-2010-2.tcx", uniqueDownloadFileName(base, existingNames = setOf(base)))
        assertEquals(
            "badminton-2026-08-26-2010-3.tcx",
            uniqueDownloadFileName(base, existingNames = setOf(base, "badminton-2026-08-26-2010-2.tcx")),
        )
    }

    @Test
    fun `resolving a collision never reuses a name already taken`() {
        val base = "badminton-2026-08-26-2010.tcx"
        val existing = setOf(base, "badminton-2026-08-26-2010-2.tcx", "badminton-2026-08-26-2010-3.tcx")
        val resolved = uniqueDownloadFileName(base, existing)
        assertTrue("\"$resolved\" collides with an existing name", resolved !in existing)
    }
}
