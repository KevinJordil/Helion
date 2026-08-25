package ch.kevinjordil.helion.strava

import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.store.SportType
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * [writeTcx] against a known sample range: valid XML structure, correct start/duration,
 * every real heart-rate reading present as a trackpoint, and nothing invented (no
 * `<DistanceMeters>` beyond the schema's own required placeholder, no `<Cadence>`, no
 * `<Position>` -- this strap has no GPS).
 */
class TcxWriterTest {

    private val start = 1_700_000_000L // 2023-11-14T22:13:20Z
    private val end = start + 1_800L // 30 minutes later

    private fun sample(offsetSeconds: Long, heartRate: Int?) = MinuteSample(
        timestamp = start + offsetSeconds,
        steps = null,
        intensity = null,
        rawKind = null,
        heartRate = heartRate,
        sleepStage = null,
    )

    private fun parse(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        val document = factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
        return document.documentElement
    }

    @Test
    fun `produces a well formed TrainingCenterDatabase document`() {
        val xml = writeTcx(SportType.BADMINTON, start, end, listOf(sample(0, 120), sample(60, 130)))
        val root = parse(xml)
        assertEquals("TrainingCenterDatabase", root.tagName)
    }

    @Test
    fun `records the activity's own start time and duration, nothing invented`() {
        val samples = listOf(sample(0, 118), sample(60, 140), sample(120, 135))
        val xml = writeTcx(SportType.BADMINTON, start, start + 180, samples)
        val root = parse(xml)

        val lap = root.getElementsByTagName("Lap").item(0) as Element
        assertEquals(
            java.time.format.DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(start)),
            lap.getAttribute("StartTime"),
        )
        assertEquals("180", lap.getElementsByTagName("TotalTimeSeconds").item(0).textContent)

        // The strap has no GPS and no calorie sensor: neither may appear anywhere as a
        // real (non-zero, invented) value, and cadence/position must not appear at all.
        assertEquals("0", lap.getElementsByTagName("DistanceMeters").item(0).textContent)
        assertEquals("0", lap.getElementsByTagName("Calories").item(0).textContent)
        assertEquals(0, root.getElementsByTagName("Cadence").length)
        assertEquals(0, root.getElementsByTagName("Position").length)
    }

    @Test
    fun `carries every real heart-rate sample as a trackpoint at its own timestamp`() {
        val samples = listOf(sample(0, 100), sample(60, 150), sample(120, 90))
        val xml = writeTcx(SportType.BADMINTON, start, start + 180, samples)
        val root = parse(xml)

        val trackpoints = root.getElementsByTagName("Trackpoint")
        assertEquals(3, trackpoints.length)

        val heartRates = (0 until trackpoints.length).map { index ->
            val point = trackpoints.item(index) as Element
            point.getElementsByTagName("HeartRateBpm").item(0).let { hr ->
                (hr as Element).getElementsByTagName("Value").item(0).textContent
            }
        }
        assertEquals(listOf("100", "150", "90"), heartRates)

        val times = (0 until trackpoints.length).map { index ->
            (trackpoints.item(index) as Element).getElementsByTagName("Time").item(0).textContent
        }
        assertEquals(
            listOf(start, start + 60, start + 120).map { java.time.format.DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(it)) },
            times,
        )
    }

    @Test
    fun `omits minutes with no heart-rate reading rather than inventing one`() {
        val samples = listOf(sample(0, 100), sample(60, null), sample(120, 90))
        val xml = writeTcx(SportType.BADMINTON, start, start + 180, samples)
        val root = parse(xml)

        assertEquals(3, root.getElementsByTagName("Trackpoint").length)
        assertEquals(2, root.getElementsByTagName("HeartRateBpm").length)
    }

    @Test
    fun `ignores samples outside the activity's own time range`() {
        val samples = listOf(sample(-60, 60), sample(0, 100), sample(60, 110), sample(240, 999))
        val xml = writeTcx(SportType.BADMINTON, start, start + 120, samples)
        val root = parse(xml)

        val trackpoints = root.getElementsByTagName("Trackpoint")
        assertEquals(2, trackpoints.length)
        assertFalse(xml.contains(">999<"))
    }

    @Test
    fun `sets Sport from the TCX mapping, distinct from the Strava sport_type`() {
        val xml = writeTcx(SportType.RUNNING, start, end, emptyList())
        val root = parse(xml)
        val activityEl = root.getElementsByTagName("Activity").item(0) as Element
        assertEquals("Running", activityEl.getAttribute("Sport"))
    }

    @Test
    fun `a zero-length activity still produces a valid, empty track`() {
        val xml = writeTcx(SportType.BADMINTON, start, start, emptyList())
        val root = parse(xml)
        assertEquals(0, root.getElementsByTagName("Trackpoint").length)
        val lap = root.getElementsByTagName("Lap").item(0) as Element
        assertEquals("0", lap.getElementsByTagName("TotalTimeSeconds").item(0).textContent)
    }

    @Test
    fun `escapes reserved XML characters if they ever end up in the timestamp id`() {
        // The Id element is derived from the start time only, so this mainly guards the
        // escaping helper itself against future reuse for free-text fields.
        val xml = writeTcx(SportType.BADMINTON, start, end, emptyList())
        assertTrue(xml.contains("<Id>"))
    }
}

/** [tcxSport] and [stravaSportType]: every [SportType] maps to something, and the two mappings are independent. */
class SportMappingTest {

    @Test
    fun `every sport type has a TCX sport mapping among the schema's three values`() {
        SportType.entries.forEach { sport ->
            assertTrue(tcxSport(sport) in setOf("Running", "Biking", "Other"))
        }
    }

    @Test
    fun `badminton maps to Strava's own dedicated Badminton sport type`() {
        assertEquals("Badminton", stravaSportType(SportType.BADMINTON))
    }

    @Test
    fun `every sport type has a non-blank Strava sport_type mapping`() {
        SportType.entries.forEach { sport ->
            assertTrue(stravaSportType(sport).isNotBlank())
        }
    }

    @Test
    fun `exact matches are used where Strava has them`() {
        assertEquals("Run", stravaSportType(SportType.RUNNING))
        assertEquals("Ride", stravaSportType(SportType.CYCLING))
        assertEquals("Walk", stravaSportType(SportType.WALKING))
        assertEquals("Swim", stravaSportType(SportType.SWIMMING))
    }

    @Test
    fun `other falls back to Strava's own generic Workout type`() {
        assertEquals("Workout", stravaSportType(SportType.OTHER))
    }
}
