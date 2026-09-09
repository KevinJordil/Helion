package ch.kevinjordil.helion.activity

import ch.kevinjordil.helion.store.HelionDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Builds every activity's [ActivityFacts] out of the archive and hands them to
 * [computeBadges].
 *
 * Badges are a property of the whole archive, not of one activity: whether a session is the
 * longest ever can only be answered by looking at all the others. So this always reads every
 * activity, and a screen showing one activity still gets its badges from a full pass. That
 * pass is one row query plus one aggregate per activity -- the aggregates run in SQLite and
 * return two numbers each (see [ch.kevinjordil.helion.store.MinuteSampleDao.heartRateSummary]),
 * so a few hundred activities cost a few hundred cheap queries, not a few hundred windows of
 * per-minute samples loaded into memory.
 */
class ActivityBadgeReader(
    private val db: HelionDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun loadBadges(): Map<Long, List<ActivityBadge>> = withContext(dispatcher) {
        val activities = db.activities().all()
        val facts = activities.map { activity ->
            val summary = db.minuteSamples().heartRateSummary(activity.startTimestamp, activity.endTimestamp)
            ActivityFacts(
                id = activity.id,
                startTimestamp = activity.startTimestamp,
                durationSeconds = activity.endTimestamp - activity.startTimestamp,
                sport = activity.sport,
                status = activity.status,
                maxHeartRate = summary.maxHeartRate,
                averageHeartRate = summary.averageHeartRate?.roundToInt(),
            )
        }
        computeBadges(facts)
    }
}
