package ch.kevinjordil.helion.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign

/**
 * One labelled figure: its name, the figure, and optionally its unit underneath.
 *
 * There used to be three of these -- one in the metric detail, one in Sommeil, one in the
 * night chart -- and they had drifted apart, one centring its column while the others
 * aligned left, so the same trio of figures read as two different components depending on
 * which screen it was on.
 *
 * [centred] carries the one rule that decides it, and it is about the shape of the row
 * rather than the screen: a figure sharing a row with siblings in equal columns is centred
 * in its own column, so the row reads as a balanced set; a figure that owns the card's full
 * width stays on the app's left-hand line ([HelionContentInset]) like every other lone
 * value. Callers pass what their row is, never what their screen prefers.
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
    centred: Boolean = false,
) {
    val colors = HelionThemeTokens.colors
    val textAlign = if (centred) TextAlign.Center else TextAlign.Start
    Column(
        modifier = modifier,
        horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Text(label, style = HelionType.labelSmall, color = colors.textTertiary, textAlign = textAlign)
        Text(value, style = HelionType.valueMedium, color = valueColor ?: colors.textPrimary, textAlign = textAlign)
        if (!unit.isNullOrEmpty()) {
            Text(unit, style = HelionType.labelSmall, color = colors.textTertiary, textAlign = textAlign)
        }
    }
}
