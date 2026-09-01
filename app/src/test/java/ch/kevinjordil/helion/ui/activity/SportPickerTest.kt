package ch.kevinjordil.helion.ui.activity

import ch.kevinjordil.helion.store.SportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [filterSports] is the searchable part of [SportPicker], deliberately pulled out as a plain
 * function (see its own kdoc) so it can be checked here without any Compose environment.
 * [labelOf] below stands in for [sportLabelRes] plus `stringResource` -- a plain lookup table
 * of the real French labels, not the actual Android resources -- exactly what [filterSports]
 * itself expects to be handed.
 */
class SportPickerTest {

    // A representative slice of the real strings.xml labels, enough to exercise matching
    // across several categories without depending on Android resources in a JVM unit test.
    private val labels = mapOf(
        SportType.BADMINTON to "Badminton",
        SportType.RUN to "Course à pied",
        SportType.RIDE to "Vélo",
        SportType.SWIM to "Natation",
        SportType.ROCK_CLIMBING to "Escalade",
        SportType.MOTORCYCLING to "Moto",
        SportType.TRAIL_RUN to "Trail",
        SportType.TABLE_TENNIS to "Tennis de table",
        SportType.TENNIS to "Tennis",
    )

    // filterSports resolves a label for every entry it walks, matched or not (see its own
    // kdoc -- it filters the full catalogue), so this must stay total over every SportType,
    // not just the handful this test cares about; an empty label for the rest never
    // accidentally matches a real query.
    private fun labelOf(sport: SportType): String = labels[sport].orEmpty()

    @Test
    fun `a blank query returns every sport type, in the enum's own order`() {
        // A blank query never has to resolve any label at all (see filterSports's own
        // early-return), so this holds even though labelOf only knows a handful of them.
        assertEquals(SportType.entries.toList(), filterSports("", ::labelOf))
        assertEquals(SportType.entries.toList(), filterSports("   ", ::labelOf))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertTrue(SportType.BADMINTON in filterSports("badminton", ::labelOf))
        assertTrue(SportType.BADMINTON in filterSports("BADMINTON", ::labelOf))
        assertTrue(SportType.BADMINTON in filterSports("BaDmInToN", ::labelOf))
    }

    @Test
    fun `matching is against the French label, never the English identifier`() {
        // "RUN" is SportType.RUN's own Kotlin identifier -- but its French label is
        // "Course à pied", which does not contain "run" at all, so a search for the
        // identifier must not match it.
        assertTrue(SportType.RUN !in filterSports("run", ::labelOf))
        assertTrue(SportType.RUN in filterSports("course", ::labelOf))
    }

    @Test
    fun `a substring anywhere in the label matches, not just a prefix`() {
        assertTrue(SportType.TABLE_TENNIS in filterSports("tennis", ::labelOf))
        assertTrue(SportType.TENNIS in filterSports("tennis", ::labelOf))
    }

    @Test
    fun `a query matching nothing returns an empty list`() {
        assertEquals(emptyList<SportType>(), filterSports("xyzzy-no-such-sport", ::labelOf))
    }

    @Test
    fun `every sport type has a distinct, stable, hyphenated slug derived from its own name`() {
        val slugs = SportType.entries.map { ch.kevinjordil.helion.store.sportSlug(it) }
        assertEquals("slugs must all be distinct", slugs.size, slugs.toSet().size)
        slugs.forEach { slug ->
            assertTrue("\"$slug\" contains an unexpected character", slug.matches(Regex("[a-z0-9-]+")))
        }
        assertEquals("badminton", ch.kevinjordil.helion.store.sportSlug(SportType.BADMINTON))
        assertEquals("rock-climbing", ch.kevinjordil.helion.store.sportSlug(SportType.ROCK_CLIMBING))
        assertEquals(
            "high-intensity-interval-training",
            ch.kevinjordil.helion.store.sportSlug(SportType.HIGH_INTENSITY_INTERVAL_TRAINING),
        )
    }
}
