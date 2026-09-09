package ch.kevinjordil.helion.activity

import ch.kevinjordil.helion.store.ActivityStatus
import ch.kevinjordil.helion.store.SportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityBadgeTest {

    private fun facts(
        id: Long,
        start: Long = id * 10_000,
        duration: Long = 3_600,
        sport: SportType? = SportType.BADMINTON,
        status: ActivityStatus = ActivityStatus.CONFIRMED,
        maxHeartRate: Int? = null,
        averageHeartRate: Int? = null,
    ) = ActivityFacts(id, start, duration, sport, status, maxHeartRate, averageHeartRate)

    @Test
    fun `an empty archive earns nothing`() {
        assertEquals(emptyMap<Long, List<ActivityBadge>>(), computeBadges(emptyList()))
    }

    @Test
    fun `the first activity in a sport earns the first-in-sport badge, and only the first`() {
        val badges = computeBadges(
            listOf(
                facts(1, sport = SportType.BADMINTON),
                facts(2, sport = SportType.BADMINTON),
                facts(3, sport = SportType.RUN),
            ),
        )
        assertTrue(badges.getValue(1).contains(ActivityBadge.FirstInSport(SportType.BADMINTON)))
        assertFalse(badges[2].orEmpty().any { it is ActivityBadge.FirstInSport })
        assertTrue(badges.getValue(3).contains(ActivityBadge.FirstInSport(SportType.RUN)))
    }

    @Test
    fun `first-in-sport follows the calendar, not the order the rows arrive in`() {
        val earlier = facts(9, start = 100)
        val later = facts(2, start = 900)
        val badges = computeBadges(listOf(later, earlier))
        assertTrue(badges.getValue(9).contains(ActivityBadge.FirstInSport(SportType.BADMINTON)))
        assertFalse(badges[2].orEmpty().any { it is ActivityBadge.FirstInSport })
    }

    @Test
    fun `the tenth counted activity earns the milestone`() {
        val badges = computeBadges((1L..12L).map { facts(it) })
        assertTrue(badges.getValue(10).contains(ActivityBadge.Milestone(10)))
        assertFalse(badges[9].orEmpty().any { it is ActivityBadge.Milestone })
        assertFalse(badges[11].orEmpty().any { it is ActivityBadge.Milestone })
    }

    @Test
    fun `an unconfirmed activity neither earns a badge nor shifts the milestone count`() {
        // Nine confirmed sessions with a candidate sitting in the middle: the tenth
        // confirmed one is still the tenth, and the candidate itself earns nothing.
        val activities = (1L..10L).map { facts(it) } + facts(99, start = 55_000, status = ActivityStatus.CANDIDATE)
        val badges = computeBadges(activities)
        assertTrue(badges.getValue(10).contains(ActivityBadge.Milestone(10)))
        assertEquals(emptyList<ActivityBadge>(), badges[99].orEmpty())
    }

    @Test
    fun `a dismissed activity takes no part`() {
        val badges = computeBadges(
            listOf(
                facts(1, duration = 3_600),
                facts(2, duration = 99_999, status = ActivityStatus.DISMISSED),
            ),
        )
        assertTrue(badges.getValue(1).contains(ActivityBadge.LongestEver))
        assertEquals(emptyList<ActivityBadge>(), badges[2].orEmpty())
    }

    @Test
    fun `the longest activity earns the record`() {
        val badges = computeBadges(
            listOf(facts(1, duration = 3_600), facts(2, duration = 7_200), facts(3, duration = 1_800)),
        )
        assertTrue(badges.getValue(2).contains(ActivityBadge.LongestEver))
        assertFalse(badges[1].orEmpty().contains(ActivityBadge.LongestEver))
    }

    @Test
    fun `a tie awards nobody the record`() {
        val badges = computeBadges(listOf(facts(1, duration = 7_200), facts(2, duration = 7_200)))
        assertFalse(badges.values.flatten().contains(ActivityBadge.LongestEver))
    }

    @Test
    fun `the longest in a sport is not repeated when it is also the longest overall`() {
        val badges = computeBadges(
            listOf(
                facts(1, duration = 7_200, sport = SportType.BADMINTON),
                facts(2, duration = 1_800, sport = SportType.RUN),
            ),
        )
        assertTrue(badges.getValue(1).contains(ActivityBadge.LongestEver))
        assertFalse(badges.getValue(1).any { it is ActivityBadge.LongestInSport })
        // The run is the only run, so it is the longest run.
        assertTrue(badges.getValue(2).contains(ActivityBadge.LongestInSport(SportType.RUN)))
    }

    @Test
    fun `the highest peak and the highest average are separate records`() {
        val badges = computeBadges(
            listOf(
                facts(1, maxHeartRate = 190, averageHeartRate = 120),
                facts(2, maxHeartRate = 170, averageHeartRate = 155),
            ),
        )
        assertTrue(badges.getValue(1).contains(ActivityBadge.MaxHeartRate))
        assertTrue(badges.getValue(2).contains(ActivityBadge.HighestIntensity))
        assertFalse(badges.getValue(1).contains(ActivityBadge.HighestIntensity))
    }

    @Test
    fun `an activity with no heart-rate sample is skipped rather than counted as zero`() {
        val badges = computeBadges(
            listOf(
                facts(1, maxHeartRate = null, averageHeartRate = null),
                facts(2, maxHeartRate = 150, averageHeartRate = 130),
            ),
        )
        assertTrue(badges.getValue(2).contains(ActivityBadge.MaxHeartRate))
        assertFalse(badges[1].orEmpty().contains(ActivityBadge.MaxHeartRate))
    }

    @Test
    fun `an activity with no sport earns no sport badge but still counts towards milestones`() {
        val activities = (1L..9L).map { facts(it) } + facts(10, start = 100_000, sport = null)
        val badges = computeBadges(activities)
        assertTrue(badges.getValue(10).contains(ActivityBadge.Milestone(10)))
        assertFalse(badges.getValue(10).any { it is ActivityBadge.FirstInSport })
    }
}
