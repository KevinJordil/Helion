package ch.kevinjordil.helion.notification

import ch.kevinjordil.helion.store.HeartRateSummary
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateSummaryTest {

    private val zone = ZoneId.of("Europe/Zurich")

    private val strings = object : CandidateStrings {
        override fun today() = "Aujourd'hui"
        override fun yesterday() = "Hier"
        override fun hoursMinutes(hours: Long, minutes: Long) = "%d h %02d".format(hours, minutes)
        override fun minutes(minutes: Long) = "$minutes min"
        override fun heartRate(average: Int, max: Int) = "FC moy. $average, max $max bpm"
    }

    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toEpochSecond()

    @Test
    fun `today's session reads day, times, duration and heart rate`() {
        val line = candidateSummary(
            strings,
            start = at("2026-11-16T18:05"),
            end = at("2026-11-16T19:20"),
            heartRate = HeartRateSummary(maxHeartRate = 171, averageHeartRate = 131.6),
            zone = zone,
            nowSeconds = at("2026-11-16T21:00"),
        )
        assertEquals("Aujourd'hui 18:05–19:20 · 1 h 15 · FC moy. 132, max 171 bpm", line)
    }

    @Test
    fun `yesterday's session says Hier, and a short one is in minutes`() {
        val line = candidateSummary(
            strings,
            start = at("2026-11-15T07:00"),
            end = at("2026-11-15T07:40"),
            heartRate = null,
            zone = zone,
            nowSeconds = at("2026-11-16T09:00"),
        )
        assertEquals("Hier 07:00–07:40 · 40 min", line)
    }

    @Test
    fun `an older session shows its date`() {
        val line = candidateSummary(
            strings,
            start = at("2026-11-12T18:00"),
            end = at("2026-11-12T19:00"),
            heartRate = null,
            zone = zone,
            nowSeconds = at("2026-11-16T09:00"),
        )
        assertEquals("jeu. 12 nov. 18:00–19:00 · 1 h 00", line)
    }

    @Test
    fun `a window without any heart-rate reading leaves the heart rate out`() {
        val line = candidateSummary(
            strings,
            start = at("2026-11-16T18:00"),
            end = at("2026-11-16T18:30"),
            heartRate = HeartRateSummary(maxHeartRate = null, averageHeartRate = null),
            zone = zone,
            nowSeconds = at("2026-11-16T20:00"),
        )
        assertEquals("Aujourd'hui 18:00–18:30 · 30 min", line)
    }
}
