package ch.kevinjordil.helion.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Helion's icon set: one linear style throughout, drawn on a 24-unit grid with a 1.9-unit
 * stroke, round caps and round joins -- the same construction as the Lucide family, so
 * every glyph sits at the same visual weight beside its neighbours. Drawn here as vectors
 * rather than pulled from material-icons-extended, whose filled style would not match and
 * which would add a large dependency for a few dozen glyphs.
 *
 * Every icon is black; [androidx.compose.material3.Icon] tints it.
 */
object HelionIcons {

    // Navigation
    val Today by lazy { icon("today", rr(3f, 3f, 7f, 9f, 1.5f), rr(14f, 3f, 7f, 5f, 1.5f), rr(14f, 12f, 7f, 9f, 1.5f), rr(3f, 16f, 7f, 5f, 1.5f)) }
    val Moon by lazy { icon("moon", "M20.5 14.4A8.5 8.5 0 1 1 9.6 3.5a6.8 6.8 0 0 0 10.9 10.9Z") }
    val Stopwatch by lazy { icon("stopwatch", circle(12f, 13.5f, 8f), "M10 2.5h4", "M12 13.5l3.2-3.2", "M19.5 6l-1.4-1.4") }
    val Sliders by lazy {
        icon("sliders", "M4 21v-7", "M4 10V3", "M12 21v-9", "M12 8V3", "M20 21v-5", "M20 12V3", "M1.5 14h5", "M9.5 8h5", "M17.5 16h5")
    }

    // Metrics
    val HeartPulse by lazy {
        icon(
            "heart-pulse",
            "M19.5 13.6C21 12.1 22 10.6 22 8.6A5.1 5.1 0 0 0 16.9 3.5c-1.8 0-3.1.6-4.9 2.4-1.8-1.8-3.1-2.4-4.9-2.4A5.1 5.1 0 0 0 2 8.6c0 2 1 3.5 2.5 5L12 21Z",
            "M3.5 12h5l1.5-2.5 2.5 5 2-3.5h6",
        )
    }
    val Footprints by lazy {
        icon(
            "footprints",
            "M4 16v-2.4C4 11.5 3 10.5 3 8c0-2.7 1.5-6 4.5-6C9.4 2 10 3.8 10 5.5c0 3.1-2 5.7-2 8.7V16a2 2 0 1 1-4 0Z",
            "M20 20v-2.4c0-2.1 1-3.1 1-5.6 0-2.7-1.5-6-4.5-6C14.6 6 14 7.8 14 9.5c0 3.1 2 5.7 2 8.7V20a2 2 0 1 0 4 0Z",
            "M16 17h4",
            "M4 13h4",
        )
    }
    val Gauge by lazy { icon("gauge", "M3.6 18.5a9.5 9.5 0 1 1 16.8 0", "M12 14.5l4.2-4.2", circle(12f, 14.5f, 1.2f)) }
    val Droplet by lazy {
        icon("droplet", "M12 21.5a7 7 0 0 0 7-7c0-2-1-3.9-3-5.5S12.5 5 12 2.5C11.5 5 10 7.4 8 9s-3 3.5-3 5.5a7 7 0 0 0 7 7Z")
    }
    val Waveform by lazy { icon("waveform", "M2 12h3.5l2.5-7 4 14 3-9 2 4.5h5") }
    val Bolt by lazy { icon("bolt", "M13 2.5 4 13.5h7.5L11 21.5l9-11h-7.5Z") }
    val Thermometer by lazy { icon("thermometer", "M14 4.5v9.9a4 4 0 1 1-4 0V4.5a2 2 0 0 1 4 0Z", "M12 17.5v-5") }
    val Wind by lazy {
        icon("wind", "M17.7 7.7A2.5 2.5 0 1 1 19.5 12H2", "M9.6 4.6A2 2 0 1 1 11 8H2", "M12.6 19.4A2 2 0 1 0 14 16H2")
    }

    // Actions and chrome
    val ChevronLeft by lazy { icon("chevron-left", "M15 18l-6-6 6-6") }
    val ChevronRight by lazy { icon("chevron-right", "M9 18l6-6-6-6") }
    val ArrowLeft by lazy { icon("arrow-left", "M19 12H5", "M12 19l-7-7 7-7") }
    val Plus by lazy { icon("plus", "M12 5v14", "M5 12h14") }
    val Check by lazy { icon("check", "M20 6 9 17l-5-5") }
    val Close by lazy { icon("close", "M18 6 6 18", "M6 6l12 12") }
    val Refresh by lazy { icon("refresh", "M20.5 12a8.5 8.5 0 1 1-2.5-6l2.5 2.5", "M20.5 3.5v5h-5") }
    val Calendar by lazy { icon("calendar", rr(3f, 4.5f, 18f, 16.5f, 2.5f), "M3 9.5h18", "M8 2.5v4", "M16 2.5v4") }
    val Clock by lazy { icon("clock", circle(12f, 12f, 9.5f), "M12 7v5l3.2 2") }
    val Flame by lazy {
        icon(
            "flame",
            "M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.4-.5-2-1-3-1.1-2.1-.2-4 2-6 .5 2.5 2 4.9 4 6.5s3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.2.4-2.3 1-3a2.5 2.5 0 0 0 2.5 2.5Z",
        )
    }
    val User by lazy { icon("user", circle(12f, 8f, 4f), "M4.5 21a7.5 7.5 0 0 1 15 0") }
    val Target by lazy { icon("target", circle(12f, 12f, 9.5f), circle(12f, 12f, 5.5f), circle(12f, 12f, 1.5f)) }
    val Bell by lazy { icon("bell", "M6 8.5a6 6 0 0 1 12 0c0 7 3 8.5 3 8.5H3s3-1.5 3-8.5", "M10.3 21a2 2 0 0 0 3.4 0") }
    val Link by lazy {
        icon(
            "link",
            "M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7",
            "M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7",
        )
    }
    val Upload by lazy { icon("upload", "M12 15V3", "M7 8l5-5 5 5", "M4 15v4a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-4") }
    val Heart by lazy {
        icon(
            "heart",
            "M19.5 13.6C21 12.1 22 10.6 22 8.6A5.1 5.1 0 0 0 16.9 3.5c-1.8 0-3.1.6-4.9 2.4-1.8-1.8-3.1-2.4-4.9-2.4A5.1 5.1 0 0 0 2 8.6c0 2 1 3.5 2.5 5L12 21Z",
        )
    }
    val Wrench by lazy {
        icon(
            "wrench",
            "M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.8-3.8a6 6 0 0 1-7.9 7.9l-6.9 6.9a2.1 2.1 0 0 1-3-3l6.9-6.9a6 6 0 0 1 7.9-7.9Z",
        )
    }
    val Info by lazy { icon("info", circle(12f, 12f, 9.5f), "M12 16.5v-5", "M12 8h.01") }
    val Pencil by lazy { icon("pencil", "M17 3.5a2.1 2.1 0 0 1 3 3L7.5 19 3 20.5 4.5 16Z", "M15 5.5l3 3") }
    val Trash by lazy { icon("trash", "M3 6h18", "M8 6V4.5A1.5 1.5 0 0 1 9.5 3h5A1.5 1.5 0 0 1 16 4.5V6", "M5.5 6l1 14a1.5 1.5 0 0 0 1.5 1.4h8a1.5 1.5 0 0 0 1.5-1.4l1-14") }
    val Alert by lazy { icon("alert", circle(12f, 12f, 9.5f), "M12 7.5v5", "M12 16.3h.01") }

    // Sport categories
    val Bike by lazy {
        icon("bike", circle(5.5f, 17f, 3.5f), circle(18.5f, 17f, 3.5f), "M5.5 17 9 9.5h6l3.5 7.5", "M12 17 9 9.5", "M8 6.5h3", "M15 9.5l-1.5-3H16")
    }
    val Waves by lazy {
        icon(
            "waves",
            "M2 7c.6.5 1.2 1 2.5 1 2.5 0 2.5-2 5-2s2.5 2 5 2 2.5-2 5-2c1.3 0 1.9.5 2.5 1",
            "M2 12.5c.6.5 1.2 1 2.5 1 2.5 0 2.5-2 5-2s2.5 2 5 2 2.5-2 5-2c1.3 0 1.9.5 2.5 1",
            "M2 18c.6.5 1.2 1 2.5 1 2.5 0 2.5-2 5-2s2.5 2 5 2 2.5-2 5-2c1.3 0 1.9.5 2.5 1",
        )
    }
    val Snowflake by lazy {
        icon("snowflake", "M12 2v20", "M3.3 7l17.4 10", "M3.3 17l17.4-10", "M9.5 3.5 12 6l2.5-2.5", "M9.5 20.5 12 18l2.5 2.5")
    }
    val Racket by lazy { icon("racket", "M19.6 4.4a4.6 6.6 45 1 1-9.3 9.3 4.6 6.6 45 1 1 9.3-9.3Z", "M10.3 13.7 3.5 20.5", "M12 8.5l3.5 3.5", "M15 5.5l3.5 3.5") }
    val Dumbbell by lazy { icon("dumbbell", "M6.5 6v12", "M17.5 6v12", "M3 9v6", "M21 9v6", "M6.5 12h11") }
    val Ball by lazy { icon("ball", circle(12f, 12f, 9.5f), "M12 7.5l4 2.9-1.5 4.6h-5L8 10.4Z", "M12 2.5v5", "M21.2 9.5 16 10.4", "M2.8 9.5 8 10.4", "M9.5 15l-3 4.5", "M14.5 15l3 4.5") }
    val Medal by lazy { icon("medal", circle(12f, 15f, 6f), "M8.5 10.2 5 2.5h4.5L12 7.5l2.5-5H19l-3.5 7.7", "M12 13v4") }

    private fun circle(cx: Float, cy: Float, r: Float): String =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0Z"

    private fun rr(x: Float, y: Float, w: Float, h: Float, r: Float): String =
        "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 -$r ${r}h-${w - 2 * r}" +
            "a$r $r 0 0 1 -$r -${r}v-${h - 2 * r}a$r $r 0 0 1 $r -${r}Z"

    private fun icon(name: String, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        paths.forEach { data ->
            builder.addPath(
                pathData = addPathNodes(data),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}
