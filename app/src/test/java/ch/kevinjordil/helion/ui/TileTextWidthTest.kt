package ch.kevinjordil.helion.ui

import ch.kevinjordil.helion.calorie.CalorieEstimator
import ch.kevinjordil.helion.ui.metric.MetricCatalog
import ch.kevinjordil.helion.ui.metric.formatValue
import ch.kevinjordil.helion.ui.settings.Sex
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
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
 * Budget: a 320dp screen, minus HomeScreen's tile row outer margin (4dp each side) and the
 * 8dp gap between the two tiles' own surfaces, split across two equal-weight tiles, minus
 * each tile's own [ch.kevinjordil.helion.ui.theme.HelionSurface] padding (10dp each side --
 * see HomeScreen's tile row, which now gives each metric its own surface instead of pairing
 * two unrelated metrics onto one):
 * `(320 - 2*4 - 8) / 2 - 2*10 = 132`dp of usable width per tile.
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

    private val tileContentWidthDp = 132f
    private val fontScale = 1.3f

    // Tile captions are sentence case, proportional [HelionType.label]/[HelionType.labelSmall]
    // now, not the uppercase, letter-spaced mono [HelionType.label] this file used to measure
    // -- see MetricTile.kt, which no longer calls `.uppercase()` at any of these call sites.
    private val labelFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexsans_medium.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private fun widthDp(text: String, fontSizeSp: Float): Float {
        val emPerChar = text.map { labelFont.advanceWidthEm(it) }
        return emPerChar.sum() * fontSizeSp * fontScale
    }

    /** [HelionType.labelSmall] (12sp): the personal-baseline compact caption -- see MetricTile.kt. */
    private fun compactCaptions() = listOf("Encore tôt", "Plus bas", "Habituel", "Plus haut")

    /** [HelionType.label] (13sp): a tile's own metric name -- see MetricTile.kt. */
    private fun tileMetricLabels() = listOf("Pas", "Stress", "SpO2", "PAI", "VFC", "Température", "Respiration")

    // "Stades (estimé)" is a full-width section title on Sommeil, not a tile label, so it
    // is not measured against the tile budget here -- see [sleepPhaseLabels]'s callers.
    // [HelionType.labelSmall] (12sp): a stage's own name, wherever it sits in a narrow
    // column (NightChart's lane labels and stat captions) -- see NightChart.kt.
    private fun sleepPhaseLabels() = listOf("Profond", "Paradoxal", "Léger", "Éveil")

    @Test
    fun `every compact tile caption fits the tile's content width at a 1_3x font scale`() {
        compactCaptions().forEach { caption ->
            val width = widthDp(caption, fontSizeSp = 12f)
            assertTrue("\"$caption\" measured ${width}dp, budget is ${tileContentWidthDp}dp", width <= tileContentWidthDp)
        }
    }

    @Test
    fun `every tile metric label fits the tile's content width at a 1_3x font scale`() {
        tileMetricLabels().forEach { label ->
            val width = widthDp(label, fontSizeSp = 13f)
            assertTrue("\"$label\" measured ${width}dp, budget is ${tileContentWidthDp}dp", width <= tileContentWidthDp)
        }
    }

    @Test
    fun `every sleep phase label fits the tile's content width at a 1_3x font scale`() {
        sleepPhaseLabels().forEach { label ->
            val width = widthDp(label, fontSizeSp = 12f)
            assertTrue("\"$label\" measured ${width}dp, budget is ${tileContentWidthDp}dp", width <= tileContentWidthDp)
        }
    }

    @Test
    fun `the full detail-screen sentence this bug was reported against does not fit -- the compact form earns its place`() {
        val fullSentence = "Plus haut que d'habitude"
        val width = widthDp(fullSentence, fontSizeSp = 12f)
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

    private val labelFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexsans_medium.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private fun widthDp(text: String): Float {
        val emPerChar = text.map { font.advanceWidthEm(it) }
        val glyphWidthSp = emPerChar.sum() * fontSizeSp
        val letterSpacingTotalSp = letterSpacingSp * text.length
        return (glyphWidthSp + letterSpacingTotalSp) * fontScale
    }

    /**
     * The three phase durations share one row across the screen (see `SleepPhaseBreakdown`),
     * so each gets a third of the content width minus the two 6dp gaps:
     * `(280 - 2*6) / 3 = 89.3`dp. Measured at StatItem's own two styles: `valueMedium` (22sp
     * mono) for the figure, `labelSmall` (12sp, sentence-case [ch.kevinjordil.helion.ui.theme.PlexSans],
     * no letter-spacing) for its caption -- see Type.kt and StatItem.
     */
    @Test
    fun `each phase duration and label fits a third-width column`() {
        val columnWidthDp = (screenContentWidthDp - 2 * 6f) / 3f

        listOf("23h59", "8h07", "0h59").forEach { value ->
            val width = (value.map { font.advanceWidthEm(it) }.sum() * 22f) * fontScale
            assertTrue(
                "value \"$value\" measured ${width}dp, a third-width column is ${columnWidthDp}dp",
                width <= columnWidthDp,
            )
        }
        listOf("Profond", "Paradoxal", "Léger").forEach { label ->
            val width = (label.map { labelFont.advanceWidthEm(it) }.sum() * 12f) * fontScale
            assertTrue(
                "label \"$label\" measured ${width}dp, a third-width column is ${columnWidthDp}dp",
                width <= columnWidthDp,
            )
        }
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
 * Confirms the night chart's fixed lane-label column (`LANE_LABEL_WIDTH`, 90dp, in
 * `NightChart.kt`) actually fits the longest of the four stage labels at
 * `HelionType.labelSmall` -- the style the lane labels are set in -- at the same 1.3x
 * accessibility font scale [TileTextWidthTest] checks tile captions against. Same
 * real-glyph measurement approach; see [TileTextWidthTest]'s kdoc for why.
 */
class HypnogramLaneLabelWidthTest {

    private val laneLabelWidthDp = 90f
    private val fontScale = 1.3f
    private val fontSizeSp = 12f

    // Sentence case, proportional [ch.kevinjordil.helion.ui.theme.PlexSans] now -- NightChart.kt
    // no longer calls `.uppercase()` on a lane label, and [HelionType.labelSmall] is no
    // longer the tracked mono style this test used to measure.
    private val font: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexsans_medium.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private fun widthDp(text: String): Float {
        val emPerChar = text.map { font.advanceWidthEm(it) }
        return emPerChar.sum() * fontSizeSp * fontScale
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
    private val unitFontSizeSp = 13f
    private val unitLetterSpacingSp = 0f
    private val spacingDp = 8f

    private val valueFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexmono_semibold.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    // The unit is [HelionType.label] now -- sentence case, proportional
    // [ch.kevinjordil.helion.ui.theme.PlexSans] -- not the tracked mono style it used to be.
    private val unitFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexsans_medium.ttf")
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
    private val unitFontSizeSp = 12f
    private val unitLetterSpacingSp = 0f

    private val valueFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexmono_semibold.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    // The unit is [HelionType.labelSmall] now -- sentence case, proportional
    // [ch.kevinjordil.helion.ui.theme.PlexSans] -- not the tracked mono style it used to be.
    private val unitFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexsans_medium.ttf")
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

    // [HelionType.label] -- sentence case, proportional [ch.kevinjordil.helion.ui.theme.PlexSans]
    // now, not the tracked mono style this class used to measure.
    private val labelFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexsans_medium.ttf")
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
            val width = widthDp(text, labelFont, fontSizeSp = 13f, letterSpacingSp = 0f)
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
        val unitWidth = widthDp("resp/min", labelFont, fontSizeSp = 12f, letterSpacingSp = 0f)
        assertTrue("\"99\" measured ${valueWidth}dp, column is ${columnWidthDp}dp", valueWidth <= columnWidthDp)
        assertTrue("\"resp/min\" measured ${unitWidth}dp, column is ${columnWidthDp}dp", unitWidth <= columnWidthDp)
    }

    @Test
    fun `the night chart's heart-rate min average and max fit their three-column row`() {
        val columnWidthDp = 88f
        val valueWidth = widthDp("224", valueFont, fontSizeSp = 22f, letterSpacingSp = 0f)
        val unitWidth = widthDp("bpm", labelFont, fontSizeSp = 12f, letterSpacingSp = 0f)
        assertTrue("\"224\" measured ${valueWidth}dp, column is ${columnWidthDp}dp", valueWidth <= columnWidthDp)
        assertTrue("\"bpm\" measured ${unitWidth}dp, column is ${columnWidthDp}dp", unitWidth <= columnWidthDp)
    }
}

/**
 * Same real-glyph-measurement approach, for the history list's row (`HistoryRow` in
 * `SleepHistory.kt`) once it grew a compact stage-composition bar between the date and the
 * duration: both of those now sit in fixed-width columns instead of `SpaceBetween`-ing
 * across the whole row, so each needs its own budget checked the same way
 * [SleepScreenWidthTest] checks the detail card's own composed strings.
 *
 * `HistoryRow` sets both the weekday+date and the duration in [HelionType.body] (IBM Plex
 * Sans regular, 15sp, no letter-spacing) rather than the mono value styles
 * [SleepScreenWidthTest] measures, so this measures against `ibmplexsans_regular.ttf`
 * instead. The row's own tag ("en cours"/"incomplet"/"estimé") is set in
 * [HelionType.labelSmall] -- sentence case, proportional now, not the tracked mono style it
 * used to be -- measured here against the same duration column it sits under.
 */
class SleepHistoryRowWidthTest {

    private val fontScale = 1.3f

    private val bodyFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexsans_regular.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    // [HelionType.labelSmall] -- sentence case, proportional
    // [ch.kevinjordil.helion.ui.theme.PlexSans] now, not the tracked mono style this class
    // used to measure.
    private val labelFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexsans_medium.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private fun bodyWidthDp(text: String): Float {
        val emPerChar = text.map { bodyFont.advanceWidthEm(it) }
        return emPerChar.sum() * 15f * fontScale
    }

    private fun tagWidthDp(text: String): Float {
        val emPerChar = text.map { labelFont.advanceWidthEm(it) }
        return emPerChar.sum() * 12f * fontScale
    }

    @Test
    fun `every weekday abbreviation next to the widest date fits the history row's date column`() {
        val columnWidthDp = 104f
        listOf("Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim").forEach { weekday ->
            val text = "$weekday 30/08"
            val width = bodyWidthDp(text)
            assertTrue("\"$text\" measured ${width}dp, column is ${columnWidthDp}dp", width <= columnWidthDp)
        }
    }

    @Test
    fun `the widest duration fits the history row's duration column`() {
        val columnWidthDp = 96f
        val width = bodyWidthDp("23 h 59")
        assertTrue("\"23 h 59\" measured ${width}dp, column is ${columnWidthDp}dp", width <= columnWidthDp)
    }

    @Test
    fun `every history row tag fits the duration column at a 1_3x font scale`() {
        val columnWidthDp = 96f
        listOf("en cours", "incomplet", "estimé").forEach { tag ->
            val width = tagWidthDp(tag)
            assertTrue("\"$tag\" measured ${width}dp, column is ${columnWidthDp}dp", width <= columnWidthDp)
        }
    }
}

/**
 * Sommeil's averages panel (`SleepScreen.kt`'s `SleepAveragesSection`): the window selector
 * (5/7/30 nuits, Tout), the "N nuits sur M" and "stades disponibles..." basis lines, and the
 * duration/awakenings/efficiency/phase figures it shares with [SleepScreenWidthTest] --
 * same 280dp root-column budget, same fonts, since this panel lives inside the same
 * `SleepScreen` root `Column`.
 */
class SleepAveragesWidthTest {

    private val rowWidthDp = 280f
    private val fontScale = 1.3f

    // [HelionType.title] and [HelionType.label] are both sentence case, proportional
    // [ch.kevinjordil.helion.ui.theme.PlexSans] now -- SleepScreen.kt no longer calls
    // `.uppercase()` on either the section title or a window-selector option.
    private val sansFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexsans_medium.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private val proseFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexsans_regular.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private fun sansWidthDp(text: String, fontSizeSp: Float): Float {
        val emPerChar = text.map { sansFont.advanceWidthEm(it) }
        return emPerChar.sum() * fontSizeSp * fontScale
    }

    private fun proseWidthDp(text: String, fontSizeSp: Float): Float {
        val emPerChar = text.map { proseFont.advanceWidthEm(it) }
        return emPerChar.sum() * fontSizeSp * fontScale
    }

    @Test
    fun `the averages section title fits a full-width row at a 1_3x font scale`() {
        val width = sansWidthDp("Moyennes", fontSizeSp = 16f)
        assertTrue("\"Moyennes\" measured ${width}dp, budget is ${rowWidthDp}dp", width <= rowWidthDp)
    }

    @Test
    fun `the four window-selector labels, laid out in one row with their real gaps, fit the panel's width`() {
        // AverageWindowSelector lays all four labels out in a single Row with 20dp gaps
        // between them (the same pattern MetricScreen's own RangeSelector uses for its
        // three options) -- unlike a set of equal-weight columns, there is no per-label
        // budget to check individually; what must fit is the row as a whole.
        val labels = listOf("5", "7", "30", "Tout")
        val totalWidth = labels.sumOf { sansWidthDp(it, fontSizeSp = 13f).toDouble() }.toFloat() + 3 * 20f
        assertTrue("selector row measured ${totalWidth}dp, budget is ${rowWidthDp}dp", totalWidth <= rowWidthDp)
    }

    @Test
    fun `the nights-basis and stage-basis lines fit within one line at their widest plausible values`() {
        val lines = listOf(
            "30 nuits sur 30",
            "Stades : 30/30 nuits",
        )
        lines.forEach { line ->
            val width = proseWidthDp(line, fontSizeSp = 13f)
            assertTrue("\"$line\" measured ${width}dp, budget is ${rowWidthDp}dp", width <= rowWidthDp)
        }
    }

    @Test
    fun `the no-nights-available message fits within two lines`() {
        val message = "Aucune nuit exploitable sur cette période."
        val width = proseWidthDp(message, fontSizeSp = 13f)
        assertTrue("\"$message\" measured ${width}dp, two-line budget is ${rowWidthDp * 2}dp", width <= rowWidthDp * 2)
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

/**
 * Activités' own fixed-width labels: sport names, activity status labels and slot
 * active-state labels, all short single tokens shown either uppercase in [HelionType.label]
 * (sport, in `SportPicker`) or uppercase in [HelionType.labelSmall] (activity status, in
 * `ActivityListScreen`/`ActivityDetailScreen`; slot active/suspended, in `SlotListScreen`).
 * Same real-glyph measurement as [TileTextWidthTest]; see its kdoc for why.
 *
 * The composed "sport · time range · duration" line each row also shows (see
 * `ActivityRow`/`SlotRow`) is deliberately not measured here: it is a single [androidx.compose.material3.Text]
 * with no `maxLines`/ellipsis, so it wraps instead of clipping regardless of length -- the
 * same reasoning [TileTextWidthTest]'s own kdoc gives for not measuring free-form prose
 * against a hard budget.
 *
 * `activity_candidate_note` -- the auto-generated "Fréquence cardiaque X-Y bpm (repos
 * habituel Z bpm)" evidence line [ch.kevinjordil.helion.activity.ActivityDetector] writes
 * onto a detected candidate's [ch.kevinjordil.helion.store.Activity.notes] -- is excluded
 * for the same reason: `ActivityDetailScreen` renders `notes` in a plain multi-line
 * [androidx.compose.material3.OutlinedTextField] with no `maxLines`, so it wraps rather
 * than clips regardless of how wide any bpm figure gets.
 */
class ActivityLabelWidthTest {

    private val rowWidthDp = 280f
    private val fontScale = 1.3f

    // [HelionType.label]/[HelionType.labelSmall] -- sentence case, proportional
    // [ch.kevinjordil.helion.ui.theme.PlexSans] now, not the tracked mono uppercase style
    // this class used to measure -- SportPicker.kt, ActivityListScreen.kt and
    // SlotListScreen.kt no longer call `.uppercase()` at any of these call sites.
    private val mediumFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexsans_medium.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private fun widthDp(text: String, font: TrueTypeFont, fontSizeSp: Float, letterSpacingSp: Float): Float {
        val emPerChar = text.map { font.advanceWidthEm(it) }
        val glyphWidthSp = emPerChar.sum() * fontSizeSp
        val letterSpacingTotalSp = letterSpacingSp * text.length
        return (glyphWidthSp + letterSpacingTotalSp) * fontScale
    }

    /**
     * Every one of [ch.kevinjordil.helion.store.SportType]'s fifty-six French labels
     * (`strings.xml`'s `sport_*` entries) -- not a hand-picked handful -- since
     * `SportPicker` now shows the whole catalogue and the longest label
     * (`sport_high_intensity_interval_training`, "Entraînement fractionné de haute
     * intensité") is exactly the case a small sample would have missed.
     */
    private fun sportLabels() = listOf(
        "Vélo", "Vélo électrique", "VTT électrique", "Gravel", "Handbike", "VTT", "Vélomobile",
        "Randonnée", "Course à pied", "Trail", "Marche",
        "Canoë", "Kayak", "Kitesurf", "Aviron", "Voile", "Paddle", "Surf", "Natation", "Planche à voile",
        "Ski alpin", "Ski de randonnée", "Patinage sur glace", "Ski de fond", "Ski à roulettes", "Snowboard", "Raquette à neige",
        "Badminton", "Padel", "Pickleball", "Racquetball", "Squash", "Tennis de table", "Tennis",
        "Crossfit", "Danse", "Vélo elliptique", "Entraînement fractionné de haute intensité", "Pilates",
        "Kinésithérapie", "Stepper", "Vélo virtuel", "Aviron virtuel", "Course virtuelle", "Musculation",
        "Entraînement", "Yoga",
        "Basketball", "Cricket", "Football", "Volleyball",
        "Golf", "Roller", "Escalade", "Skateboard", "Fauteuil roulant",
    )
    private fun activityStatusLabels() = listOf("À confirmer", "Confirmée", "Publiée", "Ignorée")
    private fun slotActiveLabels() = listOf("Active", "Suspendue")

    /**
     * "Entraînement fractionné de haute intensité" -- [SportType.HIGH_INTENSITY_INTERVAL_TRAINING]'s
     * French label -- is the one sport name in the catalogue too long for a single
     * uppercase-tracked row at this font scale; it is excluded from the one-line check
     * below and given its own two-line budget instead, since the `Text` it lands in (both
     * `SportPicker`'s own row and the activity detail screen's current-selection header)
     * wraps rather than clips -- the same "real Text wraps, never clips" reasoning
     * [NoTextClippingTest] holds every other string in this app to.
     */
    @Test
    fun `every other sport label fits a full-width row at a 1_3x font scale`() {
        (sportLabels() - "Entraînement fractionné de haute intensité").forEach { label ->
            val width = widthDp(label, mediumFont, fontSizeSp = 12f, letterSpacingSp = 1.5f)
            assertTrue("\"$label\" measured ${width}dp, budget is ${rowWidthDp}dp", width <= rowWidthDp)
        }
    }

    @Test
    fun `the longest sport label wraps within two lines rather than clipping`() {
        val label = "Entraînement fractionné de haute intensité"
        val width = widthDp(label, mediumFont, fontSizeSp = 12f, letterSpacingSp = 1.5f)
        assertTrue(
            "\"$label\" measured ${width}dp, expected it to overflow a single ${rowWidthDp}dp row " +
                "(that is exactly why it is excluded from the one-line sport label check above)",
            width > rowWidthDp,
        )
        assertTrue(
            "\"$label\" measured ${width}dp, two-line budget is ${rowWidthDp * 2}dp",
            width <= rowWidthDp * 2,
        )
    }

    @Test
    fun `every activity status label fits a full-width row at a 1_3x font scale`() {
        activityStatusLabels().forEach { label ->
            val width = widthDp(label, mediumFont, fontSizeSp = 12f, letterSpacingSp = 0f)
            assertTrue("\"$label\" measured ${width}dp, budget is ${rowWidthDp}dp", width <= rowWidthDp)
        }
    }

    @Test
    fun `every slot active-state label fits a full-width row at a 1_3x font scale`() {
        slotActiveLabels().forEach { label ->
            val width = widthDp(label, mediumFont, fontSizeSp = 12f, letterSpacingSp = 0f)
            assertTrue("\"$label\" measured ${width}dp, budget is ${rowWidthDp}dp", width <= rowWidthDp)
        }
    }
}

/**
 * The day timeline's live selection readout (`SelectionReadout`/`ReadoutItem` in
 * `DayTimelineScreen.kt`): start and end share a two-column row, each a label over a
 * [HelionType.valueMedium] value; duration gets its own full-width row below rather than a
 * third column, precisely because "23 h 59" does not fit a three-way split at this font size
 * (see `SelectionReadout`'s own kdoc) -- the same two-plus-one split
 * [SleepScreenWidthTest] already validates for Sommeil's own duration figures.
 *
 * Budgets mirror [SleepScreenWidthTest]: the screen's own 20dp-each-side padding gives the
 * same 280dp content width. Two columns with one 8dp gap: `(280 - 8) / 2 = 136`dp each; the
 * duration row gets the full 280dp.
 *
 * Widest values: a clock time is always `HH:mm` (5 characters, "23:59" is already the worst
 * case). A selection's duration cannot exceed the calendar day the timeline draws
 * ([DayTimelineReader.load] windows exactly one local day), so "23 h 59" is the same
 * genuinely-widest case [DurationTextWidthTest] uses for a night's sleep duration.
 */
class DayTimelineReadoutWidthTest {

    private val fontScale = 1.3f

    private val valueFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexmono_semibold.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private fun widthDp(text: String): Float {
        val emPerChar = text.map { valueFont.advanceWidthEm(it) }
        val glyphWidthSp = emPerChar.sum() * 22f
        return glyphWidthSp * fontScale
    }

    @Test
    fun `the widest clock time fits the two-column start-end row`() {
        val columnWidthDp = 136f
        val width = widthDp("23:59")
        assertTrue("\"23:59\" measured ${width}dp, column is ${columnWidthDp}dp", width <= columnWidthDp)
    }

    @Test
    fun `the widest possible selection duration fits its own full-width row`() {
        val rowWidthDp = 280f
        val width = widthDp("23 h 59")
        assertTrue("\"23 h 59\" measured ${width}dp, row is ${rowWidthDp}dp", width <= rowWidthDp)
    }
}

/**
 * Réglages' full-archive re-run action (`MaintenanceSettingsSection.kt`'s `ArchiveReanalysisSection`,
 * moved there from `ActivityListScreen.kt`): the button label, the in-progress label it
 * swaps to, and the status/last-run lines shown below it (`HelionType.bodySmall`, same
 * [ch.kevinjordil.helion.ui.theme.HelionType.bodySmall] prose style the rest of this file's
 * Réglages sections check their own action labels against). Same 280dp content width as
 * every other 20dp-padded screen -- see [ActivityLabelWidthTest]'s own kdoc.
 *
 * The result and last-run lines are measured at their genuinely widest plausible values: a
 * generous four-digit candidate count for the "found" message, and the fixed-width
 * `dd/MM/yyyy HH:mm` stamp ([ActivityFormat.ACTIVITY_DATETIME_FORMAT]) for the last-run
 * line, which never varies in length regardless of the actual date.
 */
class ArchiveReanalysisWidthTest {

    private val rowWidthDp = 280f
    private val fontScale = 1.3f

    private val proseFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexsans_regular.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private fun proseWidthDp(text: String): Float {
        val emPerChar = text.map { proseFont.advanceWidthEm(it) }
        return emPerChar.sum() * 13f * fontScale
    }

    private fun actionLabels() = listOf("Réanalyser tout l'historique", "Réanalyse en cours…")

    private fun statusMessages() = listOf(
        "9999 nouvelle(s) activité(s) repérée(s).",
        "Aucune nouvelle activité repérée.",
        "Une réanalyse est déjà en cours.",
        "Réanalyse annulée.",
        "Dernière réanalyse complète : 31/12/2026 23:59",
    )

    @Test
    fun `the reanalyze action label and its in-progress form fit a full-width row`() {
        actionLabels().forEach { label ->
            val width = proseWidthDp(label)
            assertTrue("\"$label\" measured ${width}dp, budget is ${rowWidthDp}dp", width <= rowWidthDp)
        }
    }

    @Test
    fun `every reanalysis status and last-run line fits within two lines at the narrowest width`() {
        statusMessages().forEach { message ->
            val width = proseWidthDp(message)
            assertTrue("\"$message\" measured ${width}dp, two-line budget is ${rowWidthDp * 2}dp", width <= rowWidthDp * 2)
        }
    }
}

/**
 * The background-sync status and battery guidance shown in Réglages' Maintenance section
 * (`MaintenanceSettingsSection.kt`'s `ArchiveReanalysisSection`, moved there from
 * `SourceSettingsSection`): the last-background-sync line at its fixed-width
 * `dd/MM/yyyy HH:mm` stamp and its never-yet form, the battery hint paragraph, and the
 * button label that opens the app's own settings page. Same 280dp content width and
 * `HelionType.bodySmall` prose style as [ArchiveReanalysisWidthTest] -- both sit in the same
 * 20dp-padded [ch.kevinjordil.helion.ui.settings.SettingsSectionScreen] container, so the
 * move changes neither.
 */
class SourceSyncStatusWidthTest {

    private val rowWidthDp = 280f
    private val fontScale = 1.3f

    private val proseFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexsans_regular.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private fun proseWidthDp(text: String, fontSizeSp: Float = 13f): Float {
        val emPerChar = text.map { proseFont.advanceWidthEm(it) }
        return emPerChar.sum() * fontSizeSp * fontScale
    }

    @Test
    fun `the last-background-sync line, in both its dated and never-yet form, fits within two lines`() {
        val lines = listOf(
            "Dernière synchronisation en arrière-plan : 31/12/2026 23:59",
            "Aucune synchronisation en arrière-plan n'a encore eu lieu.",
        )
        lines.forEach { line ->
            val width = proseWidthDp(line)
            assertTrue("\"$line\" measured ${width}dp, two-line budget is ${rowWidthDp * 2}dp", width <= rowWidthDp * 2)
        }
    }

    @Test
    fun `the battery guidance paragraph fits within five lines`() {
        val message =
            "Si cette date ne change jamais alors que l'application est fermée, le téléphone la met sans doute en veille. " +
                "Depuis la page ci-dessous : Batterie, puis 'Non restreinte'."
        val width = proseWidthDp(message)
        assertTrue("\"$message\" measured ${width}dp, five-line budget is ${rowWidthDp * 5}dp", width <= rowWidthDp * 5)
    }

    @Test
    fun `the open-app-settings button label fits a full-width row`() {
        val label = "Ouvrir les paramètres"
        val width = proseWidthDp(label, fontSizeSp = 14f)
        assertTrue("\"$label\" measured ${width}dp, budget is ${rowWidthDp}dp", width <= rowWidthDp)
    }
}

/**
 * The calorie section added to the activity detail screen (`ActivityDetailScreen.kt`): its
 * uppercase section title (`HelionType.label`, same style [ActivityLabelWidthTest] and
 * [StravaLabelWidthTest] already check other short titles against), its two plain-language
 * fallback messages and the accuracy caveat (`HelionType.bodySmall`, prose), and the composed
 * value line itself (`HelionType.body`, "≈ %d kcal") at the widest plausible figure.
 *
 * The widest figure is computed from [CalorieEstimator.kcalPerMinute] directly rather than
 * hand-typed, at the same genuinely-widest inputs the rest of this file already uses for
 * heart rate ([widestMetricValues]'s 224 bpm) and a generous, still-plausible weight (200 kg)
 * and age (99 years, a realistic ceiling for a date-of-birth field with no hard cap) sustained
 * for a full six-hour session -- a long but real upper bound for a single manually-bounded
 * activity in this app (badminton, running, cycling, walking, swimming), not an unbounded
 * worst case.
 *
 * Budget: the same 280dp content width every other 20dp-padded screen in this file uses (see
 * [SleepScreenWidthTest]'s own kdoc for the derivation) -- the activity detail screen's root
 * `Column` uses the identical 20dp horizontal padding.
 */
class CalorieLabelWidthTest {

    private val rowWidthDp = 280f
    private val fontScale = 1.3f

    // [HelionType.title] -- sentence case, proportional
    // [ch.kevinjordil.helion.ui.theme.PlexSans] now, not the tracked mono uppercase style
    // this class used to measure.
    private val labelFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexsans_medium.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private val proseFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexsans_regular.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private fun labelWidthDp(text: String): Float {
        val emPerChar = text.map { labelFont.advanceWidthEm(it) }
        return emPerChar.sum() * 16f * fontScale
    }

    private fun proseWidthDp(text: String, fontSizeSp: Float): Float {
        val emPerChar = text.map { proseFont.advanceWidthEm(it) }
        return emPerChar.sum() * fontSizeSp * fontScale
    }

    @Test
    fun `the calorie section title fits a full-width row at a 1_3x font scale`() {
        val width = labelWidthDp("Calories (estimé)")
        assertTrue("\"Calories (estimé)\" measured ${width}dp, budget is ${rowWidthDp}dp", width <= rowWidthDp)
    }

    @Test
    fun `every plain-language calorie message fits within two lines at the narrowest width`() {
        val messages = listOf(
            "Renseignez votre profil dans Réglages pour estimer les calories.",
            "Aucune fréquence cardiaque enregistrée pour cette activité : pas d'estimation possible.",
            "Précision d'environ 15-20 % : plus fiable pour comparer vos séances entre elles que comme valeur absolue.",
        )
        messages.forEach { message ->
            val width = proseWidthDp(message, fontSizeSp = 13f)
            // Prose wraps rather than clipping (NoTextClippingTest); a three-line budget is
            // used here since the accuracy note in particular is a longer sentence than the
            // two-line budget the other prose-message tests in this file hold their own
            // longer sentences to.
            assertTrue("\"$message\" measured ${width}dp, three-line budget is ${rowWidthDp * 3}dp", width <= rowWidthDp * 3)
        }
    }

    @Test
    fun `the widest plausible calorie estimate fits one line at the narrowest supported width`() {
        val widestPerMinute = CalorieEstimator.kcalPerMinute(Sex.MALE, heartRate = 224, weightKg = 200.0, ageYears = 99.0)
        val widestTotal = (widestPerMinute * 360).roundToInt() // a full six-hour session
        val text = "≈ $widestTotal kcal"
        val width = proseWidthDp(text, fontSizeSp = 15f)
        assertTrue("\"$text\" measured ${width}dp, budget is ${rowWidthDp}dp", width <= rowWidthDp)
    }
}

/**
 * The send-to-Strava section at the very bottom of the activity detail screen
 * (`ActivityDetailScreen.kt`, section label, the short mechanism note, and state/failure
 * prose, same styles [ActivityLabelWidthTest] already checks its own equivalents against),
 * sent through the owner's own server, and its Réglages configuration section
 * (`CustomServerSettingsSection.kt`'s `CustomServerSettingsSection`). Same 280dp budget as every other screen in
 * this file -- both screens share the identical 20dp-each-side padding.
 */
class CustomServerLabelWidthTest {

    private val rowWidthDp = 280f
    private val fontScale = 1.3f

    // [HelionType.headline] -- sentence case, proportional
    // [ch.kevinjordil.helion.ui.theme.PlexSans] now, not the tracked mono uppercase style
    // this class used to measure.
    private val labelFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexsans_medium.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private val proseFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexsans_regular.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private fun labelWidthDp(text: String): Float {
        val emPerChar = text.map { labelFont.advanceWidthEm(it) }
        return emPerChar.sum() * 22f * fontScale
    }

    private fun proseWidthDp(text: String): Float {
        val emPerChar = text.map { proseFont.advanceWidthEm(it) }
        return emPerChar.sum() * 13f * fontScale
    }

    private fun stateLabels() = listOf(
        "En attente",
        "Envoyée à mon serveur",
        "Déjà reçue par mon serveur",
        "Échec de l'envoi",
    )

    /**
     * The `custom_server_reason_*` sentences with a plausible worst-case detail spliced
     * into the ones carrying a `%1$s` placeholder -- deliberately long, so a report of one
     * wrapping to three-plus lines would actually show up in the two-line-budget test below.
     */
    private fun reasonMessages() = listOf(
        "Serveur non configuré. Renseignez son adresse et le jeton dans Réglages.",
        "Adresse du serveur invalide. Corrigez-la dans Réglages.",
        "Adresse en HTTP non chiffré. Confirmez l'envoi en clair dans Réglages.",
        "Connexion au serveur impossible : Unable to resolve host",
        "Jeton refusé par le serveur : HTTP 401: invalid or expired token",
        "Le serveur a refusé l'envoi : HTTP 500: internal server error, try again later",
    )

    /**
     * The owner's own server's actual sentences (see `README.md`'s custom-server section),
     * embedded exactly as [ch.kevinjordil.helion.customserver.CustomServerPublisher] and
     * [ch.kevinjordil.helion.customserver.formatServerDetail] produce them -- a real
     * server's wording, not a plausible placeholder like [reasonMessages] uses, and
     * noticeably longer for it (one embeds the settings URL in full). Checked against its
     * own, more generous budget below: unlike every other string in this file, the server's
     * own text is variable-length by design (see the module's own brief), so the `Text` it
     * lands in wraps -- it is never given `maxLines` or `TextOverflow.Ellipsis` -- rather
     * than being clipped to a fixed line count the way a bounded strings.xml sentence can
     * safely be. This still bounds it to a sane number of lines, not an unbounded one: a
     * report of a genuinely broken wrap (a single unbreakable run, say) would still show up
     * here.
     */
    private fun serverProvidedMessages() = listOf(
        "Jeton refusé par le serveur : HTTP 401: Jeton refusé : il doit être identique à celui " +
            "enregistré sur http://192.168.1.50:8787/settings.",
        "Le serveur a refusé l'envoi : HTTP 400: Champs manquants ou invalides : sport, start, duration_seconds.",
        "Le serveur a refusé l'envoi : HTTP 400: Aucun fichier .tcx reçu dans le champ « file ».",
        "Réponse du serveur : HTTP 202: Activité « test 34 » reçue (badminton, 57 min). Import Strava en cours.",
        "Réponse du serveur : HTTP 200: Déjà reçue le 26.08.2026 à 19:06 sous le titre « test 34 ». " +
            "Rien n'est renvoyé à Strava.",
    )

    @Test
    fun `the custom-server section label fits a full-width row at a 1_3x font scale`() {
        val label = "Envoi vers Strava"
        val width = labelWidthDp(label)
        assertTrue("\"$label\" measured ${width}dp, budget is ${rowWidthDp}dp", width <= rowWidthDp)
    }

    @Test
    fun `the send action label fits a full-width row`() {
        val width = proseWidthDp("Envoyer vers Strava")
        assertTrue("\"Envoyer vers Strava\" measured ${width}dp, budget is ${rowWidthDp}dp", width <= rowWidthDp)
    }

    @Test
    fun `the send-to-Strava mechanism note fits within two lines at the narrowest width`() {
        val message = "Passe par votre propre serveur, qui relaie l'activité vers Strava."
        val width = proseWidthDp(message)
        assertTrue("\"$message\" measured ${width}dp, two-line budget is ${rowWidthDp * 2}dp", width <= rowWidthDp * 2)
    }

    @Test
    fun `every custom-server state label fits one line at the narrowest supported width`() {
        stateLabels().forEach { label ->
            val width = proseWidthDp(label)
            assertTrue("\"$label\" measured ${width}dp, budget is ${rowWidthDp}dp", width <= rowWidthDp)
        }
    }

    @Test
    fun `the longest custom-server failure-reason sentence still fits within two lines at the narrowest width`() {
        reasonMessages().forEach { message ->
            val width = proseWidthDp(message)
            assertTrue("\"$message\" measured ${width}dp, two-line budget is ${rowWidthDp * 2}dp", width <= rowWidthDp * 2)
        }
    }

    @Test
    fun `the owner's own server's real sentences -- success and failure alike -- still wrap within a sane number of lines`() {
        serverProvidedMessages().forEach { message ->
            val width = proseWidthDp(message)
            assertTrue("\"$message\" measured ${width}dp, four-line budget is ${rowWidthDp * 4}dp", width <= rowWidthDp * 4)
        }
    }

    @Test
    fun `every custom-server settings field label and message fits within two lines at the narrowest width`() {
        val messages = listOf(
            "Adresse du serveur",
            "Jeton partagé",
            "Adresse invalide : indiquez une URL http:// ou https:// complète.",
            "Nom de l'appareil (Strava)",
            "Nom affiché par Strava comme appareil d'enregistrement.",
        )
        messages.forEach { message ->
            val width = proseWidthDp(message)
            assertTrue("\"$message\" measured ${width}dp, two-line budget is ${rowWidthDp * 2}dp", width <= rowWidthDp * 2)
        }
    }

    @Test
    fun `the plain-HTTP confirmation label fits within three lines, next to the checkbox it labels`() {
        // Sits in a Row next to a Checkbox (see CustomServerSettingsSection.kt's CustomServerSettingsSection),
        // not a full-width Text -- its real available width is already less than
        // rowWidthDp, so a three-line budget here is the realistic one, same reasoning
        // CalorieLabelWidthTest's own accuracy-note test uses for its longer sentence.
        val message = "Autoriser l'envoi en HTTP non chiffré (déconseillé pour des données de santé)"
        val width = proseWidthDp(message)
        assertTrue("\"$message\" measured ${width}dp, three-line budget is ${rowWidthDp * 3}dp", width <= rowWidthDp * 3)
    }
}

/**
 * The Health Connect section added to Réglages (`HealthConnectSettingsSection.kt`'s `HealthConnectSettingsSection`).
 * Same 280dp budget and 1.3x font scale as [CustomServerLabelWidthTest] -- the identical
 * 20dp-padded root `Column`.
 */
class HealthConnectLabelWidthTest {

    private val rowWidthDp = 280f
    private val fontScale = 1.3f

    // [HelionType.headline] -- sentence case, proportional
    // [ch.kevinjordil.helion.ui.theme.PlexSans] now, not the tracked mono uppercase style
    // this class used to measure.
    private val labelFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexsans_medium.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private val proseFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexsans_regular.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private fun labelWidthDp(text: String): Float {
        val emPerChar = text.map { labelFont.advanceWidthEm(it) }
        return emPerChar.sum() * 22f * fontScale
    }

    private fun proseWidthDp(text: String): Float {
        val emPerChar = text.map { proseFont.advanceWidthEm(it) }
        return emPerChar.sum() * 13f * fontScale
    }

    @Test
    fun `the Health Connect section title fits a full-width row at a 1_3x font scale`() {
        val width = labelWidthDp("Health Connect")
        assertTrue("\"Health Connect\" measured ${width}dp, budget is ${rowWidthDp}dp", width <= rowWidthDp)
    }

    @Test
    fun `the toggle label and export-now action fit a full-width row`() {
        listOf("Envoyer mes données à Health Connect", "Exporter maintenant", "Export en cours…").forEach { label ->
            val width = proseWidthDp(label)
            assertTrue("\"$label\" measured ${width}dp, two-line budget is ${rowWidthDp * 2}dp", width <= rowWidthDp * 2)
        }
    }

    @Test
    fun `the explanation paragraph -- the longest prose on this screen -- still wraps within a generous line budget`() {
        val message = "Envoie vers Health Connect (et donc vers Samsung Health) : sommeil avec ses phases mesurées " +
            "par le bracelet, activités confirmées avec leur fréquence cardiaque, ainsi que fréquence cardiaque, " +
            "pas, VFC, SpO2, température cutanée et fréquence respiratoire. Une nuit sans mesure d'appareil n'est " +
            "jamais envoyée. Une activité à confirmer ou ignorée n'est jamais envoyée."
        val width = proseWidthDp(message)
        assertTrue("measured ${width}dp, eleven-line budget is ${rowWidthDp * 11}dp", width <= rowWidthDp * 11)
    }

    @Test
    fun `the availability and permission messages each fit within two lines`() {
        val messages = listOf(
            "Health Connect n'est pas installé sur ce téléphone.",
            "L'application Health Connect installée est trop ancienne.",
            "Autorisation refusée ou révoquée : rien n'est envoyé à Health Connect.",
        )
        messages.forEach { message ->
            val width = proseWidthDp(message)
            assertTrue("\"$message\" measured ${width}dp, two-line budget is ${rowWidthDp * 2}dp", width <= rowWidthDp * 2)
        }
    }

    @Test
    fun `the last-export summary -- every count at a generous worst case -- still wraps within a sane number of lines`() {
        // Deliberately generous counts, the same "plausible worst case" spirit
        // CustomServerLabelWidthTest's own reasonMessages() uses: a full night, a few
        // activities and a whole day of point-series readings.
        val message = "Dernier export : 1 nuits, 3 activités, 1440 relevés de fréquence cardiaque, " +
            "1 jours de pas, 500 VFC, 500 SpO2, 500 températures, 500 respirations."
        val width = proseWidthDp(message)
        assertTrue("measured ${width}dp, six-line budget is ${rowWidthDp * 6}dp", width <= rowWidthDp * 6)
    }

    @Test
    fun `a failed export shows the real, potentially long underlying error within a generous line budget`() {
        val message = "Échec de l'export : java.io.IOException: Health Connect service unavailable, retry later"
        val width = proseWidthDp(message)
        assertTrue("measured ${width}dp, three-line budget is ${rowWidthDp * 3}dp", width <= rowWidthDp * 3)
    }
}

/**
 * The profile fields added to Réglages (`ProfileSettingsSection.kt`): the section title and each
 * field's label at [ch.kevinjordil.helion.ui.theme.HelionType.bodySmall]/`label`, the sex
 * options ("Homme"/"Femme") and the short privacy note underneath. Same 280dp budget as
 * [CalorieLabelWidthTest] -- `SettingsScreen`'s root `Column` uses the same 20dp padding.
 */
class ProfileFieldWidthTest {

    private val rowWidthDp = 280f
    private val fontScale = 1.3f

    // [HelionType.headline] -- sentence case, proportional
    // [ch.kevinjordil.helion.ui.theme.PlexSans] now, not the tracked mono uppercase style
    // this class used to measure.
    private val labelFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexsans_medium.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private val proseFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexsans_regular.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private fun labelWidthDp(text: String): Float {
        val emPerChar = text.map { labelFont.advanceWidthEm(it) }
        return emPerChar.sum() * 22f * fontScale
    }

    private fun proseWidthDp(text: String, fontSizeSp: Float): Float {
        val emPerChar = text.map { proseFont.advanceWidthEm(it) }
        return emPerChar.sum() * fontSizeSp * fontScale
    }

    @Test
    fun `the profile section title fits a full-width row at a 1_3x font scale`() {
        val width = labelWidthDp("Profil")
        assertTrue("\"Profil\" measured ${width}dp, budget is ${rowWidthDp}dp", width <= rowWidthDp)
    }

    @Test
    fun `every profile field label and the sex options fit a full-width row at the narrowest width`() {
        listOf("Date de naissance", "Poids (kg)", "Sexe", "Homme", "Femme").forEach { label ->
            val width = proseWidthDp(label, fontSizeSp = 13f)
            assertTrue("\"$label\" measured ${width}dp, budget is ${rowWidthDp}dp", width <= rowWidthDp)
        }
    }

    @Test
    fun `the date-of-birth invalid message and the privacy note each fit within two lines`() {
        val messages = listOf(
            "Format invalide (jj/mm/aaaa). Pas encore enregistré.",
            "Sert uniquement à estimer les calories ; reste sur l'appareil.",
        )
        messages.forEach { message ->
            val width = proseWidthDp(message, fontSizeSp = 13f)
            assertTrue("\"$message\" measured ${width}dp, two-line budget is ${rowWidthDp * 2}dp", width <= rowWidthDp * 2)
        }
    }
}

/**
 * The notifications section added to Réglages (`NotificationsSettingsSection.kt`'s `NotificationsSettingsSection`)
 * and the strings the actual system notification carries
 * (`ch.kevinjordil.helion.notification.CandidateNotifier`). Same 280dp budget as
 * [ProfileFieldWidthTest] for the Réglages section -- identical 20dp-padded root `Column` --
 * used here too for the notification's own title/text as the closest available proxy: a
 * status-bar notification has no fixed width this app controls, but a phone's narrowest
 * supported width is still the right worst case to check prose against.
 *
 * The batch text's `%1$d` placeholder is filled with 99 -- a deliberately generous count for
 * "several days without opening the app," the scenario this string is written for, not a
 * hard ceiling anywhere in the store.
 */
class NotificationLabelWidthTest {

    private val rowWidthDp = 280f
    private val fontScale = 1.3f

    // [HelionType.headline] -- sentence case, proportional
    // [ch.kevinjordil.helion.ui.theme.PlexSans] now, not the tracked mono uppercase style
    // this class used to measure.
    private val labelFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexsans_medium.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private val proseFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexsans_regular.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private fun labelWidthDp(text: String): Float {
        val emPerChar = text.map { labelFont.advanceWidthEm(it) }
        return emPerChar.sum() * 22f * fontScale
    }

    private fun proseWidthDp(text: String, fontSizeSp: Float): Float {
        val emPerChar = text.map { proseFont.advanceWidthEm(it) }
        return emPerChar.sum() * fontSizeSp * fontScale
    }

    @Test
    fun `the notifications section title fits a full-width row at a 1_3x font scale`() {
        val width = labelWidthDp("Notifications")
        assertTrue("\"Notifications\" measured ${width}dp, budget is ${rowWidthDp}dp", width <= rowWidthDp)
    }

    @Test
    fun `the toggle label fits within one line`() {
        val message = "Me notifier des activités détectées"
        val width = proseWidthDp(message, fontSizeSp = 13f)
        assertTrue("\"$message\" measured ${width}dp, budget is ${rowWidthDp}dp", width <= rowWidthDp)
    }

    @Test
    fun `the permission-missing note fits within four lines`() {
        val message =
            "Autorisation manquante : les activités candidates restent visibles dans Activités, sans notification tant qu'elle n'est pas accordée."
        val width = proseWidthDp(message, fontSizeSp = 13f)
        assertTrue("\"$message\" measured ${width}dp, four-line budget is ${rowWidthDp * 4}dp", width <= rowWidthDp * 4)
    }

    @Test
    fun `the grant-notifications button label fits a full-width row`() {
        val label = "Autoriser les notifications"
        val width = proseWidthDp(label, fontSizeSp = 14f)
        assertTrue("\"$label\" measured ${width}dp, budget is ${rowWidthDp}dp", width <= rowWidthDp)
    }

    @Test
    fun `the settings explanation fits within four lines`() {
        // The longest sentence in this section -- it spells out the "one notification per
        // candidate, never a reminder" rule in full, which is worth the extra line over a
        // terser rewording (see NoTextClippingTest for why wrapping, not clipping, is what
        // actually matters here).
        val message = "Une notification par activité candidate détectée, jamais de rappel : vous choisissez ensuite de la confirmer, la modifier ou l'ignorer."
        val width = proseWidthDp(message, fontSizeSp = 13f)
        assertTrue("\"$message\" measured ${width}dp, four-line budget is ${rowWidthDp * 4}dp", width <= rowWidthDp * 4)
    }

    @Test
    fun `the single-candidate notification title fits within one line and its text within three`() {
        val title = "Nouvelle activité détectée"
        val titleWidth = proseWidthDp(title, fontSizeSp = 14f)
        assertTrue("\"$title\" measured ${titleWidth}dp, budget is ${rowWidthDp}dp", titleWidth <= rowWidthDp)

        val text = "Une séance a été repérée sur votre bracelet. Appuyez pour la confirmer, la modifier ou l'ignorer."
        val textWidth = proseWidthDp(text, fontSizeSp = 14f)
        assertTrue("\"$text\" measured ${textWidth}dp, three-line budget is ${rowWidthDp * 3}dp", textWidth <= rowWidthDp * 3)
    }

    @Test
    fun `the batch notification title fits within one line and its text, at a generously large count, within three`() {
        val title = "Activités à vérifier"
        val titleWidth = proseWidthDp(title, fontSizeSp = 14f)
        assertTrue("\"$title\" measured ${titleWidth}dp, budget is ${rowWidthDp}dp", titleWidth <= rowWidthDp)

        val text = "99 activités ont été repérées depuis votre dernière visite. Appuyez pour les consulter."
        val textWidth = proseWidthDp(text, fontSizeSp = 14f)
        assertTrue("\"$text\" measured ${textWidth}dp, three-line budget is ${rowWidthDp * 3}dp", textWidth <= rowWidthDp * 3)
    }

    @Test
    fun `the notification channel name fits within one line and its description within two`() {
        val name = "Activités détectées"
        val nameWidth = proseWidthDp(name, fontSizeSp = 13f)
        assertTrue("\"$name\" measured ${nameWidth}dp, budget is ${rowWidthDp}dp", nameWidth <= rowWidthDp)

        val description = "Propose une activité candidate à vérifier ; ne signale jamais rien d'autre."
        val descriptionWidth = proseWidthDp(description, fontSizeSp = 13f)
        assertTrue("\"$description\" measured ${descriptionWidth}dp, two-line budget is ${rowWidthDp * 2}dp", descriptionWidth <= rowWidthDp * 2)
    }

    @Test
    fun `each notification diagnostics message fits within four lines`() {
        val messages = listOf(
            "Autorisation refusée définitivement : ouvrez les réglages de notification de Helion pour l'accorder à la main.",
            "Les notifications sont désactivées pour Helion dans les réglages du téléphone : ouvrez-les pour les réactiver.",
            "Le canal de notification est désactivé ou masqué : ouvrez ses réglages pour le réactiver.",
            "Tout est en ordre : une notification peut vous être envoyée dès qu'une activité candidate est détectée.",
        )
        messages.forEach { message ->
            val width = proseWidthDp(message, fontSizeSp = 13f)
            assertTrue("\"$message\" measured ${width}dp, four-line budget is ${rowWidthDp * 4}dp", width <= rowWidthDp * 4)
        }
    }

    @Test
    fun `every diagnostics fix-action button label and the test-notification action fit a full-width row`() {
        val labels = listOf(
            "Ouvrir les réglages Helion",
            "Ouvrir les réglages du canal",
            "Envoyer une notification de test",
        )
        labels.forEach { label ->
            val width = proseWidthDp(label, fontSizeSp = 14f)
            assertTrue("\"$label\" measured ${width}dp, budget is ${rowWidthDp}dp", width <= rowWidthDp)
        }
    }

    @Test
    fun `the intent-unavailable and test-result messages fit within two lines`() {
        val messages = listOf(
            "Impossible d'ouvrir ces réglages sur cet appareil.",
            "Notification de test envoyée : vérifiez qu'elle est bien arrivée.",
            "Échec de l'envoi : l'autorisation de notification n'est pas accordée.",
        )
        messages.forEach { message ->
            val width = proseWidthDp(message, fontSizeSp = 13f)
            assertTrue("\"$message\" measured ${width}dp, two-line budget is ${rowWidthDp * 2}dp", width <= rowWidthDp * 2)
        }
    }

    @Test
    fun `the test notification's own title and text fit the same budget as a real candidate notification`() {
        val title = "Notification de test"
        val titleWidth = proseWidthDp(title, fontSizeSp = 14f)
        assertTrue("\"$title\" measured ${titleWidth}dp, budget is ${rowWidthDp}dp", titleWidth <= rowWidthDp)

        val text = "Si vous voyez ceci, Helion peut vous notifier normalement."
        val textWidth = proseWidthDp(text, fontSizeSp = 14f)
        assertTrue("\"$text\" measured ${textWidth}dp, three-line budget is ${rowWidthDp * 3}dp", textWidth <= rowWidthDp * 3)
    }

    // The morning sleep-summary channel added alongside the candidate-detection one --
    // same 280dp budget, same fonts and sizes, since both channels share this one
    // Réglages screen (see `NotificationsSettingsSection.kt`'s `NotificationChannelSection`).

    @Test
    fun `the sleep-notification toggle label fits within one line`() {
        val message = "Me notifier une fois la nuit terminée"
        val width = proseWidthDp(message, fontSizeSp = 13f)
        assertTrue("\"$message\" measured ${width}dp, budget is ${rowWidthDp}dp", width <= rowWidthDp)
    }

    @Test
    fun `the sleep-notification explanation fits within four lines`() {
        val message = "Une notification par nuit, une fois qu'elle est terminée : la durée, sa position par rapport à vos nuits récentes et à la plage recommandée."
        val width = proseWidthDp(message, fontSizeSp = 13f)
        assertTrue("\"$message\" measured ${width}dp, four-line budget is ${rowWidthDp * 4}dp", width <= rowWidthDp * 4)
    }

    @Test
    fun `the sleep-notification permission-missing and all-clear messages fit within four lines`() {
        val messages = listOf(
            "Autorisation manquante : le résumé de la nuit ne peut pas être notifié tant qu'elle n'est pas accordée.",
            "Tout est en ordre : une notification peut vous être envoyée dès qu'une nuit est terminée.",
        )
        messages.forEach { message ->
            val width = proseWidthDp(message, fontSizeSp = 13f)
            assertTrue("\"$message\" measured ${width}dp, four-line budget is ${rowWidthDp * 4}dp", width <= rowWidthDp * 4)
        }
    }

    @Test
    fun `the sleep-notification channel name fits within one line and its description within two`() {
        val name = "Résumé du sommeil"
        val nameWidth = proseWidthDp(name, fontSizeSp = 13f)
        assertTrue("\"$name\" measured ${nameWidth}dp, budget is ${rowWidthDp}dp", nameWidth <= rowWidthDp)

        val description = "Un résumé de la nuit, une fois qu'elle est terminée."
        val descriptionWidth = proseWidthDp(description, fontSizeSp = 13f)
        assertTrue("\"$description\" measured ${descriptionWidth}dp, two-line budget is ${rowWidthDp * 2}dp", descriptionWidth <= rowWidthDp * 2)
    }

    @Test
    fun `the sleep-notification title fits within one line and its widest text within three`() {
        val title = "Votre nuit"
        val titleWidth = proseWidthDp(title, fontSizeSp = 14f)
        assertTrue("\"$title\" measured ${titleWidth}dp, budget is ${rowWidthDp}dp", titleWidth <= rowWidthDp)

        // Widest plausible composition: a full "23 h 59" duration plus the longest personal
        // and reference phrases this app has for sleep_duration.
        val text = "23 h 59 · Historique insuffisant pour l'instant · Au-dessus de la plage recommandée (7-9 h)"
        val textWidth = proseWidthDp(text, fontSizeSp = 14f)
        assertTrue("\"$text\" measured ${textWidth}dp, three-line budget is ${rowWidthDp * 3}dp", textWidth <= rowWidthDp * 3)
    }
}

/**
 * Réglages' own top level (`SettingsScreen.kt`'s `SettingsScreen`/`SettingsEntryRow`): the
 * eight [ch.kevinjordil.helion.ui.settings.SettingsSection] entry labels and their one-line
 * status previews. Both sit in the same row as a trailing ">" glyph, which is why the budget
 * here is narrower than the 280dp full-width rows the rest of this file checks -- a generous
 * fixed allowance for that glyph plus its spacing (24dp) is subtracted up front, the same
 * "measure the real container, not the whole screen" reasoning [TileTextWidthTest]'s own
 * kdoc gives for its tile budget.
 *
 * The build stamp shown as the "À propos" entry's own preview is not a `strings.xml` value
 * -- it is `BuildConfig.BUILD_STAMP`, always `dd/MM HH:mm` (see `app/build.gradle.kts`), a
 * fixed-width format regardless of the actual date, so its widest digit-only instance is
 * measured directly rather than picked from a resource list.
 */
class SettingsMenuWidthTest {

    private val entryRowWidthDp = 280f - 24f
    private val fontScale = 1.3f

    // [HelionType.label] -- sentence case, proportional [ch.kevinjordil.helion.ui.theme.PlexSans]
    // now, not the tracked mono uppercase style this class used to measure -- SettingsScreen.kt
    // no longer calls `.uppercase()` on an entry's label.
    private val labelFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexsans_medium.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private val proseFont: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexsans_regular.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private fun labelWidthDp(text: String): Float {
        val emPerChar = text.map { labelFont.advanceWidthEm(it) }
        return emPerChar.sum() * 13f * fontScale
    }

    private fun bodySmallWidthDp(text: String): Float {
        val emPerChar = text.map { proseFont.advanceWidthEm(it) }
        return emPerChar.sum() * 13f * fontScale
    }

    private fun entryTitles() = listOf(
        "Source des données", "Profil", "Objectifs", "Envoi vers Strava",
        "Health Connect", "Notifications", "Maintenance", "À propos",
    )

    @Test
    fun `every entry title fits the entry row's content width at a 1_3x font scale`() {
        entryTitles().forEach { title ->
            val width = labelWidthDp(title)
            assertTrue("\"$title\" measured ${width}dp, budget is ${entryRowWidthDp}dp", width <= entryRowWidthDp)
        }
    }

    @Test
    fun `every entry's status preview fits the entry row's content width at the widest plausible value`() {
        val previews = listOf(
            "Aucun fichier configuré", "Fichier configuré",
            "Complet", "Incomplet",
            "99999 pas",
            "Configuré", "Non configuré",
            "Activé", "Désactivé",
            "Réanalyser l'historique",
            "31/12 23:59",
        )
        previews.forEach { preview ->
            val width = bodySmallWidthDp(preview)
            assertTrue("\"$preview\" measured ${width}dp, budget is ${entryRowWidthDp}dp", width <= entryRowWidthDp)
        }
    }
}

/**
 * The axis labels the metric detail chart and Accueil's hero ribbon now draw straight onto
 * a `Canvas` (see `ScrubbableChart` in `MetricScreen.kt` and `DayRibbon.kt`) -- gridline
 * values (`Metric.formatValue`) and time-axis figures (`formatAxisTimestamp`, in
 * `ChartScrub.kt`). Unlike every Compose `Text` this file otherwise measures, a canvas
 * label has no layout system clipping or wrapping it for free: [drawText] paints exactly
 * where it is told to, so an unexpectedly wide string is not just ugly, it is a genuine
 * collision or an overflow past the edge. `ScrubbableChart` and `DayRibbon` both also clamp
 * every label's left edge into the canvas and skip a label outright rather than draw it
 * overlapping the previous one -- this test's job is to confirm the *typical* case has
 * headroom to spare, not to fall back on that skip constantly.
 *
 * Budget for the metric detail chart: the same 280dp content width [MetricHeaderWidthTest]
 * uses. Time markers land at most a few to a handful apart (see [chartTimeMarkers]'s own
 * kdoc), so the tightest case Helion's own Range selector actually produces -- Semaine's
 * full seven days, one mark per day -- is what is checked here, not the sparser five-mark
 * Jour/Mois cases. A span wider than a week that still fell into the one-mark-per-day
 * branch (up to ten days, an internal boundary [chartTimeMarkers] itself is tested against
 * but which nothing in the UI ever actually requests) would justifiably start relying on
 * [ScrubbableChart]'s own collision-skip more often; that is accepted, not a bug.
 */
class ChartAxisLabelWidthTest {

    private val chartContentWidthDp = 280f
    private val fontScale = 1.3f
    private val fontSizeSp = 11f

    // [HelionType.axisLabel]: a canvas-drawn numeral tick is still mono (see [PlexMono]'s own
    // kdoc -- numerals only), but untracked now, unlike the old [HelionType.labelSmall] this
    // class used to measure.
    private val letterSpacingSp = 0f

    private val font: TrueTypeFont by lazy {
        val file = File("src/main/res/font/ibmplexmono_medium.ttf")
        check(file.exists()) { "expected to find ${file.absolutePath} from the module's working directory" }
        TrueTypeFont.parse(file.readBytes())
    }

    private fun widthDp(text: String): Float {
        val emPerChar = text.map { font.advanceWidthEm(it) }
        val glyphWidthSp = emPerChar.sum() * fontSizeSp
        val letterSpacingTotalSp = letterSpacingSp * text.length
        return (glyphWidthSp + letterSpacingTotalSp) * fontScale
    }

    /** Every hour figure [formatAxisTimestamp] can produce for a day-long span: "0h".."23h". */
    private fun hourLabels() = (0..23).map { "${it}h" }

    /**
     * A generous sample of the day/month figures a week actually shows: at most one month
     * boundary crossing per seven-day window, so a two-digit day pairs with at most one
     * two-digit month in that window, never two two-digit fields on both marks either side
     * of the boundary at once except right at a year turnover ("31/12" next to "1/1") --
     * see the dedicated test below for that one case.
     */
    private fun dateLabels() = listOf("1/1", "9/9", "15/6", "30/6", "28/2")

    @Test
    fun `every hour axis label fits comfortably within a day span's per-mark budget`() {
        // Roughly five marks across the chart width -- see this class's own kdoc.
        val perMarkBudget = chartContentWidthDp / 5f
        hourLabels().forEach { label ->
            val width = widthDp(label)
            assertTrue("\"$label\" measured ${width}dp, per-mark budget is ${perMarkBudget}dp", width <= perMarkBudget)
        }
    }

    @Test
    fun `every date axis label fits the tightest realistic per-mark budget, a mark every day across Semaine's seven days`() {
        val perMarkBudget = chartContentWidthDp / 7f
        dateLabels().forEach { label ->
            val width = widthDp(label)
            assertTrue("\"$label\" measured ${width}dp, per-mark budget is ${perMarkBudget}dp", width <= perMarkBudget)
        }
    }

    /**
     * "31/12" beside "1/1" -- the one week of the year with a two-digit day *and* a
     * two-digit month on one mark right next to the year's shortest label on the other --
     * measures over the per-mark budget above. This is exactly what [ScrubbableChart]'s and
     * [ch.kevinjordil.helion.ui.ribbon.DayRibbon]'s own collision-skip exists for: rather
     * than let two labels overlap, one is silently dropped for that one week of the year.
     * Documented here as an accepted, deliberately-handled case, not a gap.
     */
    @Test
    fun `the year-turnover date label does not fit the per-mark budget, which is why the collision-skip exists`() {
        val perMarkBudget = chartContentWidthDp / 7f
        val width = widthDp("31/12")
        assertTrue(
            "\"31/12\" measured ${width}dp, expected it to exceed the ${perMarkBudget}dp per-mark budget",
            width > perMarkBudget,
        )
    }

    @Test
    fun `every metric's gridline value label fits the chart's own width with room to spare`() {
        MetricCatalog.all.forEach { metric ->
            val widest = widestMetricValues().getValue(metric.id)
            val label = metric.formatValue(widest)
            val width = widthDp(label)
            assertTrue(
                "\"$label\" for ${metric.id} measured ${width}dp, chart width is ${chartContentWidthDp}dp",
                width <= chartContentWidthDp,
            )
        }
    }
}
