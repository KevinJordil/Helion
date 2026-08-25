package ch.kevinjordil.helion.ui.activity

import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.ui.metric.Reading
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * One calendar day's heart rate and movement intensity, ready to plot on the manual-creation
 * timeline (see `DayTimelineScreen`). Both series reuse [Reading] -- the same shape
 * [ch.kevinjordil.helion.ui.metric.MetricScreen]'s chart already plots -- so the drawing code
 * for this screen's chart is the same shape of problem, not a new one.
 *
 * [windowStart] and [windowEnd] are that day's local midnight-to-midnight span, in Unix
 * seconds: the axis the timeline draws against even where a series has no readings, so a quiet
 * stretch of the day reads as an honest gap rather than a chart that silently shrinks to only
 * the minutes with data.
 */
data class DayTimelineState(
    val windowStart: Long,
    val windowEnd: Long,
    val heartRate: List<Reading>,
    val movement: List<Reading>,
)

/**
 * Reads [MinuteSample][ch.kevinjordil.helion.store.MinuteSample] rows for one calendar day and
 * turns them into a [DayTimelineState]. [zone] resolves the day's local midnight the same way
 * [ch.kevinjordil.helion.ui.metric.MetricReader] resolves a day boundary elsewhere in the app.
 */
class DayTimelineReader(
    private val db: HelionDatabase,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun load(date: LocalDate): DayTimelineState = withContext(dispatcher) {
        val windowStart = date.atStartOfDay(zone).toEpochSecond()
        val windowEnd = date.plusDays(1).atStartOfDay(zone).toEpochSecond()

        val samples = db.minuteSamples().between(windowStart, windowEnd)
        val heartRate = samples.mapNotNull { sample -> sample.heartRate?.let { Reading(sample.timestamp, it.toDouble()) } }
        val movement = samples.mapNotNull { sample -> sample.intensity?.let { Reading(sample.timestamp, it.toDouble()) } }

        DayTimelineState(windowStart, windowEnd, heartRate, movement)
    }
}
