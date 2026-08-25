package ch.kevinjordil.helion.activity

import ch.kevinjordil.helion.store.SportType
import ch.kevinjordil.helion.store.Slot
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [zone] is pinned to Europe/Zurich (rather than [ZoneId.systemDefault]) so these tests do
 * not depend on the machine they run on, per [occurrencesBetween]'s own contract.
 */
class SlotOccurrenceTest {

    private val zurich = ZoneId.of("Europe/Zurich")

    private fun badmintonSlot(active: Boolean = true) = Slot(
        label = "Badminton",
        dayOfWeek = DayOfWeek.TUESDAY,
        startSecondOfDay = 20 * 3600, // 20:00
        endSecondOfDay = 22 * 3600, // 22:00
        sport = SportType.BADMINTON,
        active = active,
    )

    private fun instant(iso: String) = ZonedDateTime.parse(iso).toEpochSecond()

    @Test
    fun `resolves the right instant for a single week`() {
        val slot = badmintonSlot()
        // Tuesday 2024-01-02, one week window.
        val from = instant("2024-01-01T00:00:00+01:00[Europe/Zurich]")
        val to = instant("2024-01-08T00:00:00+01:00[Europe/Zurich]")

        val occurrences = occurrencesBetween(slot, from, to, zurich)

        assertEquals(1, occurrences.size)
        assertEquals(instant("2024-01-02T20:00:00+01:00[Europe/Zurich]"), occurrences.single().start)
        assertEquals(instant("2024-01-02T22:00:00+01:00[Europe/Zurich]"), occurrences.single().end)
    }

    @Test
    fun `resolves multiple weeks in range, one occurrence per week`() {
        val slot = badmintonSlot()
        val from = instant("2024-01-01T00:00:00+01:00[Europe/Zurich]")
        val to = instant("2024-01-22T00:00:00+01:00[Europe/Zurich]")

        val occurrences = occurrencesBetween(slot, from, to, zurich)

        assertEquals(3, occurrences.size)
        assertEquals(
            listOf(
                instant("2024-01-02T20:00:00+01:00[Europe/Zurich]"),
                instant("2024-01-09T20:00:00+01:00[Europe/Zurich]"),
                instant("2024-01-16T20:00:00+01:00[Europe/Zurich]"),
            ),
            occurrences.map { it.start },
        )
    }

    @Test
    fun `an inactive slot yields no occurrences`() {
        val slot = badmintonSlot(active = false)
        val from = instant("2024-01-01T00:00:00+01:00[Europe/Zurich]")
        val to = instant("2024-01-22T00:00:00+01:00[Europe/Zurich]")

        assertTrue(occurrencesBetween(slot, from, to, zurich).isEmpty())
    }

    @Test
    fun `a slot's wall-clock time stays fixed across a spring-forward DST transition`() {
        // Europe/Zurich moves from CET (UTC+1) to CEST (UTC+2) on 2024-03-31.
        // A Tuesday slot the week before and the week after must both read 20:00 local,
        // even though the UTC instant that represents jumps by an hour.
        val slot = badmintonSlot()
        val from = instant("2024-03-25T00:00:00+01:00[Europe/Zurich]")
        val to = instant("2024-04-03T00:00:00+02:00[Europe/Zurich]")

        val occurrences = occurrencesBetween(slot, from, to, zurich)

        assertEquals(2, occurrences.size)

        val beforeTransition = occurrences[0]
        val afterTransition = occurrences[1]

        // Before the transition: CET, UTC+1 -- 20:00 local is 19:00 UTC.
        assertEquals(
            ZonedDateTime.of(2024, 3, 26, 19, 0, 0, 0, ZoneOffset.UTC).toEpochSecond(),
            beforeTransition.start,
        )
        // After the transition: CEST, UTC+2 -- 20:00 local is 18:00 UTC. If this instead
        // used fixed-offset (UTC) arithmetic from a pre-transition anchor, it would land on
        // 19:00 UTC here too, one hour off the owner's actual wall clock.
        assertEquals(
            ZonedDateTime.of(2024, 4, 2, 18, 0, 0, 0, ZoneOffset.UTC).toEpochSecond(),
            afterTransition.start,
        )

        // Both occurrences still read as exactly 20:00-22:00 local, on either side of DST.
        assertEquals(
            "20:00",
            ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(beforeTransition.start), zurich).toLocalTime().toString(),
        )
        assertEquals(
            "20:00",
            ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(afterTransition.start), zurich).toLocalTime().toString(),
        )
    }

    @Test
    fun `a slot crossing midnight ends on the next calendar day`() {
        val lateMatch = Slot(
            label = "Late match",
            dayOfWeek = DayOfWeek.FRIDAY,
            startSecondOfDay = 23 * 3600, // 23:00
            endSecondOfDay = 1 * 3600, // 01:00 the next day
            sport = SportType.BADMINTON,
        )
        val from = instant("2024-01-01T00:00:00+01:00[Europe/Zurich]")
        val to = instant("2024-01-08T00:00:00+01:00[Europe/Zurich]")

        val occurrences = occurrencesBetween(lateMatch, from, to, zurich)

        assertEquals(1, occurrences.size)
        val occurrence = occurrences.single()
        assertEquals(instant("2024-01-05T23:00:00+01:00[Europe/Zurich]"), occurrence.start)
        assertEquals(instant("2024-01-06T01:00:00+01:00[Europe/Zurich]"), occurrence.end)
        assertTrue(occurrence.end > occurrence.start)
    }

    @Test
    fun `an occurrence partially before the range is still reported if it overlaps`() {
        val lateMatch = Slot(
            label = "Late match",
            dayOfWeek = DayOfWeek.FRIDAY,
            startSecondOfDay = 23 * 3600,
            endSecondOfDay = 1 * 3600,
            sport = SportType.BADMINTON,
        )
        // Window starts right at midnight Saturday, after the slot's start but before its end.
        val from = instant("2024-01-06T00:30:00+01:00[Europe/Zurich]")
        val to = instant("2024-01-08T00:00:00+01:00[Europe/Zurich]")

        val occurrences = occurrencesBetween(lateMatch, from, to, zurich)

        assertEquals(1, occurrences.size)
        assertEquals(instant("2024-01-05T23:00:00+01:00[Europe/Zurich]"), occurrences.single().start)
    }
}
