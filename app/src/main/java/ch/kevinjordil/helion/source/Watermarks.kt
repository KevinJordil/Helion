package ch.kevinjordil.helion.source

/**
 * How far ingestion has already got, per series, in Unix seconds.
 *
 * One watermark cannot serve every series. The series do not arrive together: measured on
 * a real export, PAI is stamped slightly *ahead* of the minute stream while HRV trails it
 * by hours, even though HRV is sampled about once a minute -- that tail is transmission
 * lag, not absence of measurement. A single "highest timestamp seen anywhere" therefore
 * tracks whichever series happens to be freshest, and every slower series is filtered out
 * by `TIMESTAMP > since` for good, because the watermark never moves back.
 *
 * So each series carries its own. [minutes] covers the whole minute table (steps, heart
 * rate, sleep all share one row and one timestamp); [points] carries one entry per point
 * series, keyed by the series name the reader emits; [sessions] covers the sleep session
 * table (see [ExportReader]'s session reading) -- one watermark for the whole table, the
 * same as [minutes], since a session row is not itself a per-series value.
 *
 * Zero means "everything": an absent entry backfills that series from the beginning,
 * which is safe because every store write is idempotent on a timestamp-keyed row.
 */
data class Watermarks(
    val minutes: Long = 0,
    val points: Map<String, Long> = emptyMap(),
    val sessions: Long = 0,
) {

    fun point(series: String): Long = points[series] ?: 0

    companion object {
        /** Nothing ingested yet: read the export from the beginning. */
        val NONE = Watermarks()
    }
}
