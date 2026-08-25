package ch.kevinjordil.helion.ui

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Measures real glyph advance widths for the strings shown on Accueil's tiles -- the
 * narrowest, most crowded container in the app -- straight out of the actual .ttf file
 * this app ships (`res/font/ibmplexmono_medium.ttf`), by reading its `cmap` and `hmtx`
 * tables directly. This is how "verified, not asserted" is meant for a truncation report:
 * a real measurement against a real budget.
 *
 * Two other approaches were tried first and abandoned, for the record:
 * - `android.graphics.Paint.measureText` under Robolectric's default (non-native) graphics
 *   shadow returns exactly 1.0 per character regardless of font, size, or content -- it
 *   does not shape real glyphs, so its numbers were meaningless.
 * - `java.awt.Font` does not resolve at all in this module's unit-test compilation
 *   (`java.desktop` is not on the classpath Android Gradle Plugin builds for it).
 *
 * Parsing the TrueType tables directly sidesteps both problems and reflects the exact
 * bytes on disk, at the cost of only supporting what this test actually needs: a `cmap`
 * format-4 subtable (standard for a Latin-covering font like this one) and a plain `hmtx`
 * lookup. It is not a general-purpose font parser and is not meant to become one.
 *
 * Budget: a 320dp screen, minus HomeScreen's tile row padding (16dp each side), split
 * across two equal-weight tiles with no spacing between them, minus MetricTile's own 4dp
 * horizontal padding on each side of its content:
 * `(320 - 2*16) / 2 - 2*4 = 136`dp of usable width per tile.
 *
 * Font scale: 1.3x, a "large text" setting plenty of people run permanently (per the
 * report that prompted this file), applied to HelionType.label's 12sp size. At density 1,
 * 1sp of Android text size is exactly `1 * fontScale` px, so the measured width is directly
 * comparable to a dp budget.
 *
 * [NoTextClippingTest] is the actual, unconditional guarantee that nothing can clip even
 * if a string here ever stops fitting; this file only verifies the compact tile wording
 * was a reasonable choice.
 */
class TileTextWidthTest {

    private val tileContentWidthDp = 136f
    private val fontScale = 1.3f
    private val fontSizeSp = 12f
    private val letterSpacingSp = 1.5f

    private val font: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexmono_medium.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private fun widthDp(text: String): Float {
        val upper = text.uppercase()
        val emPerChar = upper.map { font.advanceWidthEm(it) }
        val glyphWidthSp = emPerChar.sum() * fontSizeSp
        val letterSpacingTotalSp = letterSpacingSp * upper.length
        return (glyphWidthSp + letterSpacingTotalSp) * fontScale
    }

    private fun compactCaptions() = listOf("Encore tôt", "Plus bas", "Habituel", "Plus haut")

    private fun tileMetricLabels() = listOf("Pas", "Stress", "SpO2", "PAI", "VFC", "Température")

    @Test
    fun `every compact tile caption fits the tile's content width at a 1_3x font scale`() {
        compactCaptions().forEach { caption ->
            val width = widthDp(caption)
            assertTrue("\"$caption\" measured ${width}dp, budget is ${tileContentWidthDp}dp", width <= tileContentWidthDp)
        }
    }

    @Test
    fun `every tile metric label fits the tile's content width at a 1_3x font scale`() {
        tileMetricLabels().forEach { label ->
            val width = widthDp(label)
            assertTrue("\"$label\" measured ${width}dp, budget is ${tileContentWidthDp}dp", width <= tileContentWidthDp)
        }
    }

    @Test
    fun `the full detail-screen sentence this bug was reported against does not fit -- the compact form earns its place`() {
        val fullSentence = "Plus haut que d'habitude"
        val width = widthDp(fullSentence)
        assertTrue(
            "expected \"$fullSentence\" ($width dp) to overflow the ${tileContentWidthDp}dp tile budget, " +
                "which is exactly what was reported clipped",
            width > tileContentWidthDp,
        )
    }
}

/**
 * The minimum of a TrueType font needed to answer one question: how wide, in em units, is
 * a given character's glyph. Parses only `head` (unitsPerEm), `maxp` (glyph count), `hhea`
 * (how many `hmtx` entries carry their own width), `hmtx` (the widths), and a `cmap`
 * format-4 subtable (character -> glyph). See [TileTextWidthTest]'s kdoc for why this
 * exists instead of using a real font-shaping engine.
 */
private class TrueTypeFont(
    private val unitsPerEm: Int,
    private val advanceWidths: IntArray,
    private val cmap: Map<Int, Int>,
) {
    /** Advance width of [char]'s glyph, as a fraction of the font's em square. Falls back
     * to a full em for anything not in the font's cmap (must not silently measure as 0,
     * which would make an unsupported character look free). */
    fun advanceWidthEm(char: Char): Float {
        val glyphId = cmap[char.code] ?: return 1f
        val width = advanceWidths.getOrElse(glyphId) { advanceWidths.last() }
        return width.toFloat() / unitsPerEm
    }

    companion object {
        fun parse(bytes: ByteArray): TrueTypeFont {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val numTables = buf.getShort(4).toInt() and 0xFFFF
            val tables = mutableMapOf<String, Pair<Int, Int>>() // tag -> (offset, length)
            for (i in 0 until numTables) {
                val entryOffset = 12 + i * 16
                val tag = String(bytes, entryOffset, 4, Charsets.US_ASCII)
                val offset = buf.getInt(entryOffset + 8)
                val length = buf.getInt(entryOffset + 12)
                tables[tag] = offset to length
            }

            val headOffset = tables.getValue("head").first
            val unitsPerEm = buf.getShort(headOffset + 18).toInt() and 0xFFFF

            val maxpOffset = tables.getValue("maxp").first
            val numGlyphs = buf.getShort(maxpOffset + 4).toInt() and 0xFFFF

            val hheaOffset = tables.getValue("hhea").first
            val numOfLongHorMetrics = buf.getShort(hheaOffset + 34).toInt() and 0xFFFF

            val hmtxOffset = tables.getValue("hmtx").first
            val advanceWidths = IntArray(numGlyphs)
            var lastWidth = 0
            for (g in 0 until numGlyphs) {
                lastWidth = if (g < numOfLongHorMetrics) {
                    buf.getShort(hmtxOffset + g * 4).toInt() and 0xFFFF
                } else {
                    lastWidth
                }
                advanceWidths[g] = lastWidth
            }

            val cmap = parseCmap(buf, bytes, tables.getValue("cmap").first)
            return TrueTypeFont(unitsPerEm, advanceWidths, cmap)
        }

        private fun parseCmap(buf: ByteBuffer, bytes: ByteArray, cmapOffset: Int): Map<Int, Int> {
            val numSubtables = buf.getShort(cmapOffset + 2).toInt() and 0xFFFF
            var subtableOffset = -1
            for (i in 0 until numSubtables) {
                val entryOffset = cmapOffset + 4 + i * 8
                val platformId = buf.getShort(entryOffset).toInt() and 0xFFFF
                val encodingId = buf.getShort(entryOffset + 2).toInt() and 0xFFFF
                val offset = buf.getInt(entryOffset + 4)
                // Prefer Windows/Unicode BMP (3,1); accept plain Unicode (0,*) otherwise.
                if (platformId == 3 && encodingId == 1) {
                    subtableOffset = cmapOffset + offset
                    break
                }
                if (platformId == 0 && subtableOffset == -1) {
                    subtableOffset = cmapOffset + offset
                }
            }
            check(subtableOffset != -1) { "no usable cmap subtable found" }

            val format = buf.getShort(subtableOffset).toInt() and 0xFFFF
            check(format == 4) { "expected cmap format 4, found $format" }

            val segCountX2 = buf.getShort(subtableOffset + 6).toInt() and 0xFFFF
            val segCount = segCountX2 / 2
            val endCodeOffset = subtableOffset + 14
            val startCodeOffset = endCodeOffset + segCountX2 + 2 // + reservedPad
            val idDeltaOffset = startCodeOffset + segCountX2
            val idRangeOffsetOffset = idDeltaOffset + segCountX2

            val result = mutableMapOf<Int, Int>()
            for (seg in 0 until segCount) {
                val endCode = buf.getShort(endCodeOffset + seg * 2).toInt() and 0xFFFF
                val startCode = buf.getShort(startCodeOffset + seg * 2).toInt() and 0xFFFF
                val idDelta = buf.getShort(idDeltaOffset + seg * 2).toInt()
                val idRangeOffset = buf.getShort(idRangeOffsetOffset + seg * 2).toInt() and 0xFFFF
                if (startCode == 0xFFFF && endCode == 0xFFFF) continue

                for (code in startCode..endCode) {
                    val glyphId = if (idRangeOffset == 0) {
                        (code + idDelta) and 0xFFFF
                    } else {
                        val glyphIndexAddress = idRangeOffsetOffset + seg * 2 + idRangeOffset + 2 * (code - startCode)
                        val raw = buf.getShort(glyphIndexAddress).toInt() and 0xFFFF
                        if (raw == 0) 0 else (raw + idDelta) and 0xFFFF
                    }
                    if (glyphId != 0) result[code] = glyphId
                }
            }
            return result
        }
    }
}
