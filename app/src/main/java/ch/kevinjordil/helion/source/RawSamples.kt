package ch.kevinjordil.helion.source

import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.store.PointSample

/** Sleep stage as stored in MinuteSample.sleepStage. */
object SleepStage {
    const val AWAKE = 0
    const val LIGHT = 1
    const val DEEP = 2
    const val REM = 3
}

data class RawSamples(
    val minutes: List<MinuteSample>,
    val points: List<PointSample>,
)
