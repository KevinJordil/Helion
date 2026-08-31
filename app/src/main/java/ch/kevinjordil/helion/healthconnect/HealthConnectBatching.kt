package ch.kevinjordil.helion.healthconnect

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord

/**
 * Health Connect's own hard ceiling on one `insertRecords` call's total serialised size --
 * this is the exact number the owner's device reported back
 * (`Records chunk size exceeded the max chunk limit: 5000000, was: 7038836`), and the whole
 * reason [HealthConnectExporter] never hands Health Connect everything it has in one call.
 */
const val HEALTH_CONNECT_MAX_CHUNK_BYTES = 5_000_000

/**
 * The byte budget this app actually batches against -- well under
 * [HEALTH_CONNECT_MAX_CHUNK_BYTES], not right up against it.
 *
 * [estimateRecordBytes] is a guess, not the true wire size Health Connect measures: this app
 * has no way to run the same protobuf-over-Binder serialisation Health Connect itself uses
 * before actually making the call. So the budget leaves real headroom rather than aiming at
 * the documented ceiling -- 40% of it -- so that even if a real record's encoding runs
 * meaningfully heavier than this file's estimate, a batch built to this budget still lands
 * under the actual cap. It stays large enough that a typical incremental pass (a handful of
 * new days, one night of sleep, one activity) still goes out in a single batch rather than
 * being split needlessly.
 */
const val HEALTH_CONNECT_BATCH_BYTE_BUDGET = 2_000_000

/**
 * A deliberately generous flat estimate of one record's fixed footprint: its
 * [androidx.health.connect.client.records.metadata.Metadata]
 * (device manufacturer and model strings, a clientRecordId, a version), two instants, two
 * nullable zone offsets, and a type tag -- the shape every record kind [HealthConnectExporter]
 * writes shares. Not measured against Health Connect's actual wire format (there is no
 * client-side way to do that); picked to comfortably exceed a plain record's real footprint
 * rather than approximate it tightly.
 */
private const val RECORD_BASE_OVERHEAD_BYTES = 256

/**
 * Per-entry overhead for a record that carries a list -- [HeartRateRecord.samples] or
 * [SleepSessionRecord.stages], the only two kinds this app ever produces with more than a
 * handful of entries. Each entry is little more than an [java.time.Instant] and one small
 * value (a heart rate, a stage code), well under this per-entry estimate even accounting for
 * whatever framing Health Connect's own serialisation adds around each one.
 */
private const val SERIES_ENTRY_OVERHEAD_BYTES = 64

/**
 * A conservative upper-bound estimate of how many bytes [record] contributes to one
 * `insertRecords` call -- see this file's own kdoc for why this stays a rough, generous guess
 * rather than a measurement. [ExerciseSessionRecord.title] and
 * [ExerciseSessionRecord.notes] are the one place this app writes free text of arbitrary
 * length (an activity's own name and the owner's own notes on it), so those are sized for
 * real rather than folded into the flat base estimate.
 */
fun estimateRecordBytes(record: Record): Int = RECORD_BASE_OVERHEAD_BYTES + when (record) {
    is HeartRateRecord -> record.samples.size * SERIES_ENTRY_OVERHEAD_BYTES
    is SleepSessionRecord -> record.stages.size * SERIES_ENTRY_OVERHEAD_BYTES
    is ExerciseSessionRecord -> (record.title?.length ?: 0) + (record.notes?.length ?: 0)
    else -> 0
}

/**
 * Greedily groups [items] into batches, each kept under [byteBudget] by [sizeOf]'s running
 * total -- never splitting one item across two batches, only ever deciding where one batch
 * ends and the next begins. An item whose own estimate already exceeds [byteBudget] still
 * gets a batch of its own rather than being dropped: better to try it alone against Health
 * Connect's real limit and surface a genuine failure than to silently skip it.
 *
 * Order is preserved: [items] is walked once, filling the current batch before starting a
 * new one, so a caller can always relate a batch back to a contiguous slice of what it was
 * about to write -- what makes partial-progress accounting after a failure meaningful (see
 * [HealthConnectExporter]).
 */
fun <T> batchBySize(items: List<T>, byteBudget: Int, sizeOf: (T) -> Int): List<List<T>> {
    if (items.isEmpty()) return emptyList()
    val batches = mutableListOf<List<T>>()
    var current = mutableListOf<T>()
    var currentBytes = 0
    for (item in items) {
        val size = sizeOf(item)
        if (current.isNotEmpty() && currentBytes + size > byteBudget) {
            batches += current
            current = mutableListOf()
            currentBytes = 0
        }
        current += item
        currentBytes += size
    }
    if (current.isNotEmpty()) batches += current
    return batches
}

/** [batchBySize] specialised to plain Health Connect [Record]s, sized by [estimateRecordBytes]. */
fun batchRecordsBySize(records: List<Record>, byteBudget: Int = HEALTH_CONNECT_BATCH_BYTE_BUDGET): List<List<Record>> =
    batchBySize(records, byteBudget, ::estimateRecordBytes)
