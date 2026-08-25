package ch.kevinjordil.helion.source

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hand-built fixtures only -- no data from a real export. [buildBlob] assembles a blob in
 * the same layout [parseSleepSessionBlob] documents: an 8-byte header (unused by the
 * parser itself), a configurable run of zero padding, the segment count and array, more
 * padding out to a fixed size, and the 8-byte footer of totals.
 */
class SleepSessionBlobTest {

    private fun buildBlob(
        segments: List<Triple<Int, Int, Int>>,
        totals: DeviceSleepTotals,
        paddingBeforeCount: Int = 20,
        trailingPadding: Int = 40,
        totalSize: Int? = null,
    ): ByteArray {
        val headerAndPadding = 8 + paddingBeforeCount
        val segmentBytes = 5 * segments.size
        val bodySize = headerAndPadding + 2 + segmentBytes + trailingPadding
        val size = totalSize ?: (bodySize + 8)
        val data = ByteArray(size)
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // Header: not read by the parser, filled with non-zero bytes so it cannot be
        // mistaken for the padding run itself.
        buffer.putInt(0, 0x11111111)
        buffer.putInt(4, 0x22222222)

        var offset = headerAndPadding
        buffer.putShort(offset, segments.size.toShort())
        offset += 2
        segments.forEach { (start, end, type) ->
            buffer.putShort(offset, start.toShort())
            buffer.putShort(offset + 2, end.toShort())
            data[offset + 4] = type.toByte()
            offset += 5
        }

        val footerStart = size - 8
        buffer.putShort(footerStart, totals.remMinutes.toShort())
        buffer.putShort(footerStart + 2, totals.lightMinutes.toShort())
        buffer.putShort(footerStart + 4, totals.deepMinutes.toShort())
        buffer.putShort(footerStart + 6, totals.awakeMinutes.toShort())

        return data
    }

    @Test
    fun `a well-formed blob decodes to its segments and matching totals`() {
        val segments = listOf(
            Triple(100, 129, DeviceSleepStage.LIGHT), // 30 min
            Triple(130, 149, DeviceSleepStage.DEEP), // 20 min
            Triple(150, 154, DeviceSleepStage.AWAKE), // 5 min
            Triple(155, 179, DeviceSleepStage.REM), // 25 min
        )
        val totals = DeviceSleepTotals(remMinutes = 25, lightMinutes = 30, deepMinutes = 20, awakeMinutes = 5)
        val blob = buildBlob(segments, totals)

        val result = parseSleepSessionBlob(blob)
        check(result is SleepSessionBlobResult.Parsed) { "expected a parse, got $result" }
        assertEquals(4, result.segments.size)
        assertEquals(totals, result.totals)
        assertEquals(100, result.segments.first().startMinute)
        assertEquals(179, result.segments.last().endMinute)
        assertTrue(result.segments.all { it.type in DeviceSleepStage.KNOWN })
    }

    @Test
    fun `the padding run can be a different length without breaking the parse`() {
        // The spec explicitly warns the padding length between rows is not fixed --
        // this proves the parser does not secretly assume one.
        val segments = listOf(Triple(0, 29, DeviceSleepStage.LIGHT))
        val totals = DeviceSleepTotals(remMinutes = 0, lightMinutes = 30, deepMinutes = 0, awakeMinutes = 0)
        val shortPadding = buildBlob(segments, totals, paddingBeforeCount = 16)
        val longPadding = buildBlob(segments, totals, paddingBeforeCount = 200)

        assertTrue(parseSleepSessionBlob(shortPadding) is SleepSessionBlobResult.Parsed)
        assertTrue(parseSleepSessionBlob(longPadding) is SleepSessionBlobResult.Parsed)
    }

    @Test
    fun `a buffer too short for a header and footer is rejected`() {
        val result = parseSleepSessionBlob(ByteArray(10))
        assertTrue(result is SleepSessionBlobResult.Invalid)
    }

    @Test
    fun `a blob with no segment data at all is rejected, not read as zero segments`() {
        // All zero from byte 8 to the footer: no padding run ever ends, because there is
        // no real header, count, or segments after it -- a session with no hypnogram.
        val blob = ByteArray(594)
        val result = parseSleepSessionBlob(blob)
        assertTrue(result is SleepSessionBlobResult.Invalid)
    }

    @Test
    fun `an implausible segment count is rejected`() {
        val data = ByteArray(594)
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, 1)
        buffer.putInt(4, 1)
        // A real run of zero padding, then a count that could never fit this blob --
        // observed for real as a count of 2589 in a corrupt row.
        buffer.putShort(84, 2589.toShort())

        val result = parseSleepSessionBlob(data)
        check(result is SleepSessionBlobResult.Invalid)
        assertTrue(result.reason.contains("count", ignoreCase = true))
    }

    @Test
    fun `a segment with an unknown type is rejected`() {
        val segments = listOf(Triple(0, 29, 99)) // 99 is not a known device stage type
        val totals = DeviceSleepTotals(remMinutes = 0, lightMinutes = 0, deepMinutes = 0, awakeMinutes = 0)
        val blob = buildBlob(segments, totals)

        val result = parseSleepSessionBlob(blob)
        check(result is SleepSessionBlobResult.Invalid)
        assertTrue(result.reason.contains("type", ignoreCase = true))
    }

    @Test
    fun `per-type sums disagreeing with the trailing totals is rejected`() {
        val segments = listOf(Triple(0, 29, DeviceSleepStage.LIGHT)) // 30 real light minutes
        val wrongTotals = DeviceSleepTotals(remMinutes = 0, lightMinutes = 31, deepMinutes = 0, awakeMinutes = 0)
        val blob = buildBlob(segments, wrongTotals)

        val result = parseSleepSessionBlob(blob)
        check(result is SleepSessionBlobResult.Invalid)
        assertTrue(result.reason.contains("disagree", ignoreCase = true))
    }

    @Test
    fun `a segment array that would overrun the footer is rejected`() {
        // A count that is individually plausible but, combined with the actual buffer
        // size, would read segment bytes into (or past) the footer.
        val data = ByteArray(50)
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, 1)
        buffer.putInt(4, 1)
        var i = 8
        while (i < 24) { data[i] = 0; i++ }
        buffer.putShort(24, 10.toShort()) // 10 segments * 5 bytes = 50 bytes, blob is only 50 total

        val result = parseSleepSessionBlob(data)
        assertTrue(result is SleepSessionBlobResult.Invalid)
    }
}
