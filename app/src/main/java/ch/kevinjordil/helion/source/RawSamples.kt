package ch.kevinjordil.helion.source

import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.store.PointSample
import ch.kevinjordil.helion.store.SleepStageSegment

/**
 * Sleep stage as stored in MinuteSample.sleepStage.
 *
 * Only AWAKE/ASLEEP: see the comment on ExportReader.sleepStageOf for why finer
 * stages are not derived from this export.
 */
object SleepStage {
    const val AWAKE = 0
    const val ASLEEP = 1
}

data class RawSamples(
    val minutes: List<MinuteSample>,
    val points: List<PointSample>,
    val stageSegments: List<SleepStageSegment> = emptyList(),
)
