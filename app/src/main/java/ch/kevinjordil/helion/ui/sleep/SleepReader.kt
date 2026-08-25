package ch.kevinjordil.helion.ui.sleep

import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.ui.metric.Reading
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** How far back nights are read for the history list and the personal baseline behind it. */
private const val LOOKBACK_DAYS = 30L

private const val RESPIRATORY_RATE_SERIES = "respiratory_rate"

/**
 * Reads minute samples for the lookback window, segments them into [SleepEpisode]s (see
 * [segmentSleepEpisodes]), keeps only the ones classified as a night, and attaches each
 * one's respiratory rate readings from the point-series table -- a join
 * [segmentSleepEpisodes] itself cannot do, since it only ever sees
 * [ch.kevinjordil.helion.store.MinuteSample]s.
 *
 * [zone] follows the same pattern as [ch.kevinjordil.helion.ui.metric.MetricReader]:
 * defaults to the device's zone, and tests pass a fixed one so date attribution does not
 * depend on the machine running them.
 */
class SleepReader(
    private val db: HelionDatabase,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val thresholds: SleepThresholds = SleepThresholds(),
) {

    /** Nights only, most recent last, over the last [LOOKBACK_DAYS] days. */
    suspend fun loadNights(now: Long): List<SleepEpisode> = withContext(dispatcher) {
        val from = now - LOOKBACK_DAYS * 86_400
        val minutes = db.minuteSamples().between(from, now)
        val episodes = segmentSleepEpisodes(minutes, zone, now, thresholds)
        episodes
            .filter { it.kind == SleepEpisodeKind.NIGHT }
            .sortedBy { it.wokeAt }
            .map { episode ->
                val respiratoryRate = db.pointSamples()
                    .between(RESPIRATORY_RATE_SERIES, episode.fellAsleepAt, episode.wokeAt)
                    .map { Reading(it.timestamp, it.value) }
                    .sortedBy { it.timestamp }
                episode.copy(respiratoryRateReadings = respiratoryRate)
            }
    }
}
