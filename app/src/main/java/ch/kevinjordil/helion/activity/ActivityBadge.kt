package ch.kevinjordil.helion.activity

import ch.kevinjordil.helion.store.ActivityStatus
import ch.kevinjordil.helion.store.SportType

/**
 * The milestones worth marking, in the order a badge list shows them. Appending is safe;
 * reordering only changes presentation order, never which activity earns what.
 */
private val MILESTONE_COUNTS = listOf(10, 25, 50, 100, 250, 500, 1000)

/**
 * Everything [computeBadges] needs about one activity. A flat record rather than the
 * [ch.kevinjordil.helion.store.Activity] row itself, so the rule engine stays a pure
 * function over plain data and can be tested without a database.
 *
 * [maxHeartRate] and [averageHeartRate] are null for an activity whose window holds no
 * heart-rate sample at all -- the band was off the wrist, or the archive has a gap there.
 * A null never earns or blocks a heart-rate badge; it simply does not take part.
 */
data class ActivityFacts(
    val id: Long,
    val startTimestamp: Long,
    val durationSeconds: Long,
    val sport: SportType?,
    val status: ActivityStatus,
    val maxHeartRate: Int? = null,
    val averageHeartRate: Int? = null,
)

/**
 * A mark earned by one activity, relative to every other activity in the archive.
 *
 * These are descriptions of a record, not scores: nothing here ranks the owner against
 * anyone else, and nothing is awarded for doing less. There is deliberately no
 * "shortest activity" badge even though it is the natural mirror of [LongestEver] -- it
 * would reward the least effort, and in practice it would settle permanently on whichever
 * session got truncated by a flat battery, which is a recording artefact rather than
 * something that happened.
 */
sealed interface ActivityBadge {

    /** The first activity ever recorded in this sport. */
    data class FirstInSport(val sport: SportType) : ActivityBadge

    /** The 10th, 25th, 50th... activity overall -- see [MILESTONE_COUNTS]. */
    data class Milestone(val count: Int) : ActivityBadge

    /** Longer than every other counted activity. */
    data object LongestEver : ActivityBadge

    /** Longer than every other counted activity in the same sport (and not already [LongestEver]). */
    data class LongestInSport(val sport: SportType) : ActivityBadge

    /** Holds the highest heart rate ever recorded inside an activity. */
    data object MaxHeartRate : ActivityBadge

    /** Holds the highest average heart rate ever sustained across an activity. */
    data object HighestIntensity : ActivityBadge
}

/**
 * Which badges each activity has earned, keyed by [ActivityFacts.id].
 *
 * Only [ActivityStatus.CONFIRMED] and [ActivityStatus.PUBLISHED] activities take part, on
 * both sides of every comparison: a candidate is a proposal the owner has not yet agreed
 * happened, so it can neither earn a record nor take one away from a session that did
 * happen. A dismissed activity is an explicit "this was not a session" and is likewise out.
 * This is also what keeps badges stable: confirming a session can add badges, and nothing
 * else moves them.
 *
 * Every record is decided by a strict comparison, so a tie awards nobody rather than
 * awarding everybody -- two 90-minute sessions do not both get "longest ever". The
 * exception is [ActivityBadge.FirstInSport] and [ActivityBadge.Milestone], which are
 * positional: they are settled by [ActivityFacts.startTimestamp] ascending, with
 * [ActivityFacts.id] breaking an exact tie so the result never depends on input order.
 */
fun computeBadges(activities: List<ActivityFacts>): Map<Long, List<ActivityBadge>> {
    val counted = activities
        .filter { it.status == ActivityStatus.CONFIRMED || it.status == ActivityStatus.PUBLISHED }
        .sortedWith(compareBy({ it.startTimestamp }, { it.id }))
    if (counted.isEmpty()) return emptyMap()

    val badges = mutableMapOf<Long, MutableList<ActivityBadge>>()
    fun award(id: Long, badge: ActivityBadge) {
        badges.getOrPut(id) { mutableListOf() }.add(badge)
    }

    val seenSports = mutableSetOf<SportType>()
    counted.forEachIndexed { index, activity ->
        activity.sport?.let { sport ->
            if (seenSports.add(sport)) award(activity.id, ActivityBadge.FirstInSport(sport))
        }
        val position = index + 1
        if (position in MILESTONE_COUNTS) award(activity.id, ActivityBadge.Milestone(position))
    }

    val longest = soleMaxBy(counted) { it.durationSeconds }
    longest?.let { award(it.id, ActivityBadge.LongestEver) }

    counted.groupBy { it.sport }.forEach { (sport, inSport) ->
        if (sport == null) return@forEach
        val longestInSport = soleMaxBy(inSport) { it.durationSeconds } ?: return@forEach
        // The overall record already says everything this one would; two badges for the
        // same fact on the same card is noise.
        if (longestInSport.id != longest?.id) {
            award(longestInSport.id, ActivityBadge.LongestInSport(sport))
        }
    }

    soleMaxBy(counted.filter { it.maxHeartRate != null }) { it.maxHeartRate!!.toLong() }
        ?.let { award(it.id, ActivityBadge.MaxHeartRate) }
    soleMaxBy(counted.filter { it.averageHeartRate != null }) { it.averageHeartRate!!.toLong() }
        ?.let { award(it.id, ActivityBadge.HighestIntensity) }

    return badges
}

/**
 * The single entry holding the strict maximum of [selector], or null when the list is empty
 * or when two or more entries share the top value. A shared record is not a record.
 */
private fun <T> soleMaxBy(items: List<T>, selector: (T) -> Long): T? {
    if (items.isEmpty()) return null
    val top = items.maxOf(selector)
    val holders = items.filter { selector(it) == top }
    return holders.singleOrNull()
}
