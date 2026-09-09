package ch.kevinjordil.helion.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * One labelled figure: its name, the figure, and optionally its unit underneath.
 *
 * There used to be three of these -- one in the metric detail, one in Sommeil, one in the
 * night chart -- and they had drifted apart: the metric detail centred its column while the
 * other two aligned left, so the same trio of figures read as two different components
 * depending on which screen it was on. Everything else in this app is aligned to the
 * left-hand line (see [HelionContentInset]), so that is what this does.
 *
 * The unit sits on its own line below the value rather than beside it, so the widest value
 * alone -- not "value unit" together -- is what has to fit the column. See MetricStatsWidthTest.
 */
@Composable
fun HelionStatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    valueColor: Color? = null,
) {
    val colors = HelionThemeTokens.colors
    Column(modifier = modifier) {
        Text(label, style = HelionType.labelSmall, color = colors.textTertiary)
        Text(value, style = HelionType.valueMedium, color = valueColor ?: colors.textPrimary)
        if (!unit.isNullOrEmpty()) {
            Text(unit, style = HelionType.labelSmall, color = colors.textTertiary)
        }
    }
}
