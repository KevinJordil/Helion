package ch.kevinjordil.helion.source

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The device's own stage type codes, as they appear in [HUAMI_SLEEP_SESSION_SAMPLE]'s
 * `DATA` blob -- see [parseSleepSessionBlob]. Not the same numbering as
 * [ch.kevinjordil.helion.ui.sleep.SleepPhase]; that mapping happens at the UI boundary,
 * not here, so this file stays a faithful transcription of what the device actually sends.
 */
object DeviceSleepStage {
    const val LIGHT = 4
    const val DEEP = 5
    const val AWAKE = 7
    const val REM = 8

    val KNOWN = setOf(LIGHT, DEEP, AWAKE, REM)
}

/**
 * One contiguous stretch of a single stage, as reported by the device. [startMinute] and
 * [endMinute] are both inclusive, minute offsets from the session's own start-of-day
 * midnight (see [parseSleepSessionBlob]'s kdoc for that base) -- not yet absolute
 * timestamps; the caller (see [ch.kevinjordil.helion.source.ExportReader]) combines them
 * with the session's own midnight field to produce those.
 */
data class DeviceStageSegment(val startMinute: Int, val endMinute: Int, val type: Int) {
    /** Minutes this segment covers, inclusive of both ends. */
    val durationMinutes: Int get() = endMinute - startMinute + 1
}

/** The four trailing per-type totals a blob carries, in minutes, exactly as ordered in the blob. */
data class DeviceSleepTotals(val remMinutes: Int, val lightMinutes: Int, val deepMinutes: Int, val awakeMinutes: Int)

sealed class SleepSessionBlobResult {
    data class Parsed(val segments: List<DeviceStageSegment>, val totals: DeviceSleepTotals) : SleepSessionBlobResult()

    /** The blob failed one of [parseSleepSessionBlob]'s validation checks -- see [reason]. */
    data class Invalid(val reason: String) : SleepSessionBlobResult()
}

/** Every blob has exactly this many trailing bytes: four uint16 totals (REM, light, deep, awake). */
private const val FOOTER_BYTES = 8

/** Bytes per encoded segment: uint16 start, uint16 end, uint8 type. */
private const val SEGMENT_BYTES = 5

/**
 * A segment count above this is never real data -- a 594-byte blob cannot possibly hold
 * this many 5-byte segments before running into the 8-byte footer, so a count this large
 * can only be garbage (a misaligned read, a corrupt row). Chosen well above any plausible
 * night: even one segment per minute for a two-day span is under 3000, but a single
 * genuine minute-granularity night is nowhere close -- the real device output classifies
 * in coarse multi-minute runs, observed at 27-36 segments for a full night. 500 leaves
 * generous headroom while still catching the kind of overflow this guards against (a real
 * corrupt row was observed with a "count" of 2589).
 */
private const val MAX_PLAUSIBLE_SEGMENT_COUNT = 500

/** How many consecutive zero bytes after the fixed 8-byte header count as "the padding run" -- see [parseSleepSessionBlob]. */
private const val MIN_PADDING_RUN = 16

/**
 * Decodes one `HUAMI_SLEEP_SESSION_SAMPLE.DATA` blob into its stage segments and trailing
 * totals.
 *
 * Layout (reverse-engineered and verified against a real export, see the source comment
 * history for the verification): bytes 0-3 are a uint32 LE session-end timestamp (seconds
 * -- equal to the row's own TIMESTAMP column, which this function does not need and does
 * not read), bytes 4-7 a uint32 LE midnight of the day the session ends on (also not read
 * here -- combining it with the segments' minute offsets is [ExportReader]'s job, not
 * this decoder's). From byte 8 onward there is more fixed-format header, then a run of
 * zero-byte padding of variable length (never hardcode where it ends -- that offset moved
 * between real rows in the same export), then a uint16 LE segment count and that many
 * 5-byte segments (uint16 start, uint16 end, uint8 type -- see [DeviceSleepStage]). The
 * segment array is itself followed by more padding out to a fixed blob size; the four
 * trailing uint16 totals (REM, light, deep, awake -- in minutes) always sit in the blob's
 * very last 8 bytes, regardless of how many segments came before them.
 *
 * Rejects (returns [SleepSessionBlobResult.Invalid], never throws) rather than emitting
 * a partial or wrong track when: the blob is too short to hold a header and footer at
 * all; no plausible padding run is found; the segment count is implausible or would run
 * the segment array past the footer; any segment carries a type outside
 * [DeviceSleepStage.KNOWN]; or the segments' own per-type sums disagree with the
 * trailing totals -- the one cross-check this format offers for free, and the strongest
 * signal that a blob was misread.
 */
fun parseSleepSessionBlob(data: ByteArray): SleepSessionBlobResult {
    if (data.size < 8 + FOOTER_BYTES) return SleepSessionBlobResult.Invalid("blob too short for header and footer")

    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val footerStart = data.size - FOOTER_BYTES

    val paddingEnd = findPaddingEnd(data, footerStart) ?: return SleepSessionBlobResult.Invalid("no segment block found")
    if (paddingEnd + 2 > footerStart) return SleepSessionBlobResult.Invalid("segment count would overrun the footer")

    val count = buffer.getShort(paddingEnd).toInt() and 0xFFFF
    if (count == 0) return SleepSessionBlobResult.Invalid("segment count is zero")
    if (count > MAX_PLAUSIBLE_SEGMENT_COUNT) return SleepSessionBlobResult.Invalid("implausible segment count: $count")

    val segmentsStart = paddingEnd + 2
    val segmentsEnd = segmentsStart + SEGMENT_BYTES * count
    if (segmentsEnd > footerStart) return SleepSessionBlobResult.Invalid("segment array would overrun the footer")

    val segments = ArrayList<DeviceStageSegment>(count)
    for (i in 0 until count) {
        val offset = segmentsStart + SEGMENT_BYTES * i
        val start = buffer.getShort(offset).toInt() and 0xFFFF
        val end = buffer.getShort(offset + 2).toInt() and 0xFFFF
        val type = data[offset + 4].toInt() and 0xFF
        if (type !in DeviceSleepStage.KNOWN) return SleepSessionBlobResult.Invalid("unknown segment type: $type")
        if (end < start) return SleepSessionBlobResult.Invalid("segment end before start at index $i")
        segments.add(DeviceStageSegment(start, end, type))
    }

    val totals = DeviceSleepTotals(
        remMinutes = buffer.getShort(footerStart).toInt() and 0xFFFF,
        lightMinutes = buffer.getShort(footerStart + 2).toInt() and 0xFFFF,
        deepMinutes = buffer.getShort(footerStart + 4).toInt() and 0xFFFF,
        awakeMinutes = buffer.getShort(footerStart + 6).toInt() and 0xFFFF,
    )

    val sums = segments.groupingBy { it.type }.fold(0) { total, segment -> total + segment.durationMinutes }
    val computed = DeviceSleepTotals(
        remMinutes = sums[DeviceSleepStage.REM] ?: 0,
        lightMinutes = sums[DeviceSleepStage.LIGHT] ?: 0,
        deepMinutes = sums[DeviceSleepStage.DEEP] ?: 0,
        awakeMinutes = sums[DeviceSleepStage.AWAKE] ?: 0,
    )
    if (computed != totals) {
        return SleepSessionBlobResult.Invalid("per-type sums $computed disagree with trailing totals $totals")
    }

    return SleepSessionBlobResult.Parsed(segments, totals)
}

/**
 * Scans forward from byte 8 (right after the fixed end/midnight header) for a run of at
 * least [MIN_PADDING_RUN] zero bytes, and returns the offset right after that run -- where
 * the segment count is expected to start. A short run of zero bytes inside the header's
 * own fixed fields does not count; only a run at least this long is treated as the actual
 * padding block, which is what makes this robust to the padding length varying between
 * rows instead of relying on a fixed offset. Returns null if no such run exists before
 * [footerStart] -- e.g. a session with no stage data at all, whose buffer is zero from
 * shortly after the header straight through to the footer.
 */
private fun findPaddingEnd(data: ByteArray, footerStart: Int): Int? {
    var i = 8
    while (i < footerStart) {
        if (data[i] == 0.toByte()) {
            var j = i
            while (j < footerStart && data[j] == 0.toByte()) j++
            if (j - i >= MIN_PADDING_RUN) return j
            i = j
        } else {
            i++
        }
    }
    return null
}
