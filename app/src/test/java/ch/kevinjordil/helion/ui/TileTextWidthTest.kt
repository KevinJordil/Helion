package ch.kevinjordil.helion.ui

import ch.kevinjordil.helion.ui.metric.MetricCatalog
import ch.kevinjordil.helion.ui.metric.formatValue
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

    private fun tileMetricLabels() = listOf("Pas", "Stress", "SpO2", "PAI", "VFC", "Température", "Respiration")

    // "Stades (estimé)" is a full-width section title on Sommeil, not a tile label, so it
    // is not measured against the tile budget here -- see [sleepPhaseLabels]'s callers.
    private fun sleepPhaseLabels() = listOf("Profond", "Paradoxal", "Léger", "Éveil")

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
    fun `every sleep phase label fits the tile's content width at a 1_3x font scale`() {
        sleepPhaseLabels().forEach { label ->
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
 * Same real-glyph-measurement approach as [TileTextWidthTest], for the one report that
 * prompted this file's twin: Sommeil's duration numeral wrapping onto a second line for a
 * ten-hour-plus night (see `SLEEP_DURATION_STYLE` in `SleepScreen.kt`).
 *
 * Budget: the app's narrowest supported screen (320dp) minus Sommeil's own horizontal
 * padding (20dp each side, see `SleepScreen`'s root `Column`): `320 - 2*20 = 280`dp.
 *
 * Widest value: "23 h 59" -- the format is `"%d h %02d"`, and a night's *asleep* duration
 * cannot exceed its own span, which itself cannot exceed a calendar day's worth of
 * continuously-asleep minutes in any real recording. Two-digit hours and two-digit minutes
 * is therefore the genuinely widest case this format is engineered to guarantee, not just
 * the typical one -- see [TileTextWidthTest]'s own kdoc on why a real measurement, not an
 * assertion, is what "verified" means here.
 */
class DurationTextWidthTest {

    private val screenContentWidthDp = 280f
    private val fontScale = 1.3f
    private val fontSizeSp = 40f
    private val letterSpacingSp = -1f

    private val font: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexmono_semibold.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private fun widthDp(text: String): Float {
        val emPerChar = text.map { font.advanceWidthEm(it) }
        val glyphWidthSp = emPerChar.sum() * fontSizeSp
        val letterSpacingTotalSp = letterSpacingSp * text.length
        return (glyphWidthSp + letterSpacingTotalSp) * fontScale
    }

    @Test
    fun `the widest possible sleep duration fits one line at the narrowest supported width`() {
        val widest = "23 h 59"
        val width = widthDp(widest)
        assertTrue(
            "\"$widest\" measured ${width}dp at ${fontSizeSp}sp, budget is ${screenContentWidthDp}dp -- " +
                "shrink SLEEP_DURATION_STYLE further",
            width <= screenContentWidthDp,
        )
    }
}

/**
 * Confirms the hypnogram's fixed lane-label column (`HypnogramRibbon`'s 90dp label width,
 * in `DayRibbon.kt`) actually fits the longest of the four stage labels at
 * `HelionType.labelSmall` -- the style the lane labels are set in -- at the same 1.3x
 * accessibility font scale [TileTextWidthTest] checks tile captions against. Same
 * real-glyph measurement approach; see [TileTextWidthTest]'s kdoc for why.
 */
class HypnogramLaneLabelWidthTest {

    private val laneLabelWidthDp = 90f
    private val fontScale = 1.3f
    private val fontSizeSp = 11f
    private val letterSpacingSp = 1f

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

    @Test
    fun `every hypnogram lane label fits its 90dp column at a 1_3x font scale`() {
        listOf("Éveil", "Paradoxal", "Léger", "Profond").forEach { label ->
            val width = widthDp(label)
            assertTrue("\"$label\" measured ${width}dp, column is ${laneLabelWidthDp}dp", width <= laneLabelWidthDp)
        }
    }
}

/**
 * Every metric's widest plausible raw value, for [MetricHeaderWidthTest] and
 * [MetricStatsWidthTest]. Not a typical value -- the point of both tests, per the reports
 * that prompted them, is to catch the case that actually overflows:
 * - `heart_rate`, `temperature`: [MetricCatalog]'s own `plausibleRange` upper bound.
 * - `steps`: a five-digit daily total (`noteRes` calls it a daily sum; a very active day
 *   is comfortably five digits).
 * - `stress`: its description states the scale runs 0 to 100.
 * - `spo2`: a percentage cannot exceed 100.
 * - `pai`, `hrv`: neither has a documented ceiling (PAI is a rolling weekly index the
 *   description only gives a floor for; HRV/RMSSD has no upper bound in the store), so a
 *   generously large three-digit-plus-decimal value is used rather than guessing a typical
 *   one.
 * - `respiratory_rate`: generously above any real breathing rate, still just two digits --
 *   this is one of the short-number metrics the report was about.
 */
private fun widestMetricValues(): Map<String, Double> = mapOf(
    "heart_rate" to 224.0,
    "steps" to 99_999.0,
    "stress" to 100.0,
    "spo2" to 100.0,
    "pai" to 999.9,
    "hrv" to 999.9,
    "temperature" to 42.0,
    "respiratory_rate" to 99.0,
)

/** The unit text each metric renders next to its value, straight from `strings.xml`. */
private fun unitFor(metricId: String): String = when (metricId) {
    "heart_rate" -> "bpm"
    "steps" -> "pas"
    "stress", "pai" -> ""
    "spo2" -> "%"
    "hrv" -> "ms"
    "temperature" -> "°C"
    "respiratory_rate" -> "resp/min"
    else -> error("unhandled metric id $metricId -- add its unit above")
}

/**
 * The gap that let "15 resp/min" wrap through four reports: [TileTextWidthTest] and its
 * siblings only measure strings pulled straight from `strings.xml`, never text composed at
 * runtime from a formatted value plus a unit. MetricScreen's own header value is exactly
 * that composition (see `DETAIL_VALUE_STYLE`'s Row in `MetricScreen.kt`): a big value in
 * `DETAIL_VALUE_STYLE` (56sp, `ibmplexmono_semibold`) next to its unit in the much smaller
 * `label` style (12sp, `ibmplexmono_medium`), 8dp apart. This generates that composed text
 * for every metric in [MetricCatalog] at its widest plausible value (see
 * [widestMetricValues]) and measures both pieces for real, the same glyph-table approach as
 * [TileTextWidthTest].
 *
 * Budget: the app's narrowest supported screen (320dp) minus MetricScreen's own horizontal
 * padding (20dp each side, see `MetricScreen`'s root `Column`): `320 - 2*20 = 280`dp.
 */
class MetricHeaderWidthTest {

    private val screenContentWidthDp = 280f
    private val fontScale = 1.3f
    private val valueFontSizeSp = 56f
    private val valueLetterSpacingSp = -1f
    private val unitFontSizeSp = 12f
    private val unitLetterSpacingSp = 1.5f
    private val spacingDp = 8f

    private val valueFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexmono_semibold.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private val unitFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexmono_medium.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private fun widthDp(text: String, font: TrueTypeFont, fontSizeSp: Float, letterSpacingSp: Float): Float {
        val emPerChar = text.map { font.advanceWidthEm(it) }
        val glyphWidthSp = emPerChar.sum() * fontSizeSp
        val letterSpacingTotalSp = letterSpacingSp * text.length
        return (glyphWidthSp + letterSpacingTotalSp) * fontScale
    }

    @Test
    fun `every metric's widest header value and unit fit one line at the narrowest supported width`() {
        MetricCatalog.all.forEach { metric ->
            val widest = widestMetricValues().getValue(metric.id)
            val value = metric.formatValue(widest)
            val unit = unitFor(metric.id)

            val valueWidth = widthDp(value, valueFont, valueFontSizeSp, valueLetterSpacingSp)
            val unitWidth = if (unit.isEmpty()) 0f else widthDp(unit, unitFont, unitFontSizeSp, unitLetterSpacingSp)
            val spacing = if (unit.isEmpty()) 0f else spacingDp * fontScale
            val total = valueWidth + spacing + unitWidth

            assertTrue(
                "\"$value $unit\" for ${metric.id} measured ${total}dp, budget is ${screenContentWidthDp}dp",
                total <= screenContentWidthDp,
            )
        }
    }
}

/**
 * Same composed-string gap as [MetricHeaderWidthTest], for the min/max/average row
 * (`StatsRow`/`StatItem` in `MetricScreen.kt`). Each `StatItem` is an equal-weight column of
 * the row (three columns, 8dp apart) with the value (`valueMedium`, 22sp,
 * `ibmplexmono_semibold`) and its unit (`labelSmall`, 11sp, `ibmplexmono_medium`) stacked on
 * separate lines rather than side by side, so what has to fit a column is the value alone
 * and the unit alone, not the two concatenated.
 *
 * Budget: the same 280dp content width as [MetricHeaderWidthTest], split across three
 * columns with two 8dp gaps between them: `(280 - 2*8) / 3 = 88`dp per column.
 */
class MetricStatsWidthTest {

    private val columnWidthDp = 88f
    private val fontScale = 1.3f
    private val valueFontSizeSp = 22f
    private val unitFontSizeSp = 11f
    private val unitLetterSpacingSp = 1f

    private val valueFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexmono_semibold.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private val unitFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexmono_medium.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private fun widthDp(text: String, font: TrueTypeFont, fontSizeSp: Float, letterSpacingSp: Float): Float {
        val emPerChar = text.map { font.advanceWidthEm(it) }
        val glyphWidthSp = emPerChar.sum() * fontSizeSp
        val letterSpacingTotalSp = letterSpacingSp * text.length
        return (glyphWidthSp + letterSpacingTotalSp) * fontScale
    }

    @Test
    fun `every metric's widest min-max-average value fits its stats column at the narrowest supported width`() {
        MetricCatalog.all.forEach { metric ->
            val widest = widestMetricValues().getValue(metric.id)
            val value = metric.formatValue(widest)
            val width = widthDp(value, valueFont, valueFontSizeSp, 0f)
            assertTrue(
                "\"$value\" for ${metric.id} measured ${width}dp, column is ${columnWidthDp}dp",
                width <= columnWidthDp,
            )
        }
    }

    @Test
    fun `every metric's unit fits its stats column at the narrowest supported width`() {
        MetricCatalog.all.forEach { metric ->
            val unit = unitFor(metric.id)
            if (unit.isEmpty()) return@forEach
            val width = widthDp(unit, unitFont, unitFontSizeSp, unitLetterSpacingSp)
            assertTrue(
                "\"$unit\" for ${metric.id} measured ${width}dp, column is ${columnWidthDp}dp",
                width <= columnWidthDp,
            )
        }
    }
}

/**
 * Same composed-string gap as [MetricHeaderWidthTest]/[MetricStatsWidthTest], now closed for
 * Sommeil's own stat blocks. The report that prompted this class was specifically
 * "resp/min" wrapping the respiratory max onto a third line, but that gap was never
 * screen-specific: every value-plus-unit and stat figure on Sommeil
 * (the weekday+date header, awakenings, efficiency, the fell-asleep/woke times, the
 * deep/REM/light phase durations, respiratory min/average/max, and the night chart's own
 * heart-rate min/average/max) is measured here the same real-glyph way, at its own widest
 * plausible value, so the next four-times-reported clipping bug is caught before a fifth
 * report.
 *
 * Budgets mirror [MetricHeaderWidthTest]/[MetricStatsWidthTest]: Sommeil's root `Column` has
 * the same 20dp-each-side padding, so the same 280dp content width applies. Rows of two
 * equal-weight columns (8dp apart, see `SleepScreen.kt`) get `(280 - 8) / 2 = 136`dp each;
 * rows of three ((280 - 16) / 3 = 88dp) match [MetricStatsWidthTest]'s own column exactly,
 * since `StatItem` here now uses the identical value/unit-on-its-own-line split.
 *
 * The weekday+date header sits between two Material3 `IconButton`s (48dp minimum touch
 * target each, previous/next night), not the full content width:
 * `280 - 2*48 = 184`dp.
 */
class SleepScreenWidthTest {

    private val fontScale = 1.3f

    private val labelFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexmono_medium.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private val valueFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexmono_semibold.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private fun widthDp(text: String, font: TrueTypeFont, fontSizeSp: Float, letterSpacingSp: Float): Float {
        val emPerChar = text.map { font.advanceWidthEm(it) }
        val glyphWidthSp = emPerChar.sum() * fontSizeSp
        val letterSpacingTotalSp = letterSpacingSp * text.length
        return (glyphWidthSp + letterSpacingTotalSp) * fontScale
    }

    @Test
    fun `every weekday abbreviation next to the widest date fits between the two nav icon buttons`() {
        val headerBudgetDp = 184f
        listOf("Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim").forEach { weekday ->
            val text = "$weekday 30/08"
            val width = widthDp(text, labelFont, fontSizeSp = 12f, letterSpacingSp = 1.5f)
            assertTrue("\"$text\" measured ${width}dp, budget is ${headerBudgetDp}dp", width <= headerBudgetDp)
        }
    }

    @Test
    fun `awakenings and efficiency fit their own full-width row at the widest plausible values`() {
        // Each is its own full-width line (see SleepScreen.kt) rather than sharing a row:
        // "12 · 24 min" is already a composed count-plus-duration phrase, and a single
        // awakening is capped just under SleepThresholds.maxBriefAwakeningMinutes (20) --
        // a generous, still-plausible disturbed night is used here rather than an
        // unbounded worst case, the same judgement call MetricStatsWidthTest's own kdoc
        // makes for PAI/HRV.
        val rowWidthDp = 280f
        val awakenings = "20 · 250 min"
        val efficiency = "100 %"
        listOf(awakenings, efficiency).forEach { value ->
            val width = widthDp(value, valueFont, fontSizeSp = 22f, letterSpacingSp = 0f)
            assertTrue("\"$value\" measured ${width}dp, row is ${rowWidthDp}dp", width <= rowWidthDp)
        }
    }

    @Test
    fun `fell-asleep and woke times fit their two-column row`() {
        val columnWidthDp = 136f
        val width = widthDp("23:59", valueFont, fontSizeSp = 22f, letterSpacingSp = 0f)
        assertTrue("\"23:59\" measured ${width}dp, column is ${columnWidthDp}dp", width <= columnWidthDp)
    }

    @Test
    fun `deep and REM phase durations fit their two-column row at the widest plausible value`() {
        // Two columns, not three (see SleepScreen.kt): the third, light sleep, gets its own
        // full-width row below instead.
        val columnWidthDp = 136f
        // Same reasoning as DurationTextWidthTest's "23 h 59": no phase can occupy more of
        // the night than the night's own (bounded) span.
        val width = widthDp("23 h 59", valueFont, fontSizeSp = 22f, letterSpacingSp = 0f)
        assertTrue("\"23 h 59\" measured ${width}dp, column is ${columnWidthDp}dp", width <= columnWidthDp)
    }

    @Test
    fun `light phase duration fits its own full-width row at the widest plausible value`() {
        val rowWidthDp = 280f
        val width = widthDp("23 h 59", valueFont, fontSizeSp = 22f, letterSpacingSp = 0f)
        assertTrue("\"23 h 59\" measured ${width}dp, row is ${rowWidthDp}dp", width <= rowWidthDp)
    }

    @Test
    fun `respiratory min average and max fit their three-column row, value and unit each on their own line`() {
        val columnWidthDp = 88f
        val valueWidth = widthDp("99", valueFont, fontSizeSp = 22f, letterSpacingSp = 0f)
        val unitWidth = widthDp("resp/min", labelFont, fontSizeSp = 11f, letterSpacingSp = 1f)
        assertTrue("\"99\" measured ${valueWidth}dp, column is ${columnWidthDp}dp", valueWidth <= columnWidthDp)
        assertTrue("\"resp/min\" measured ${unitWidth}dp, column is ${columnWidthDp}dp", unitWidth <= columnWidthDp)
    }

    @Test
    fun `the night chart's heart-rate min average and max fit their three-column row`() {
        val columnWidthDp = 88f
        val valueWidth = widthDp("224", valueFont, fontSizeSp = 22f, letterSpacingSp = 0f)
        val unitWidth = widthDp("bpm", labelFont, fontSizeSp = 11f, letterSpacingSp = 1f)
        assertTrue("\"224\" measured ${valueWidth}dp, column is ${columnWidthDp}dp", valueWidth <= columnWidthDp)
        assertTrue("\"bpm\" measured ${unitWidth}dp, column is ${columnWidthDp}dp", unitWidth <= columnWidthDp)
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
