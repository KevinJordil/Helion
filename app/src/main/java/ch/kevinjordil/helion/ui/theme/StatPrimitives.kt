package ch.kevinjordil.helion.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * One labelled figure: a compact caption above, the figure below with its unit beside it.
 *
 * [centred] follows the shape of the row rather than the screen: a figure sharing a row with
 * siblings in equal columns is centred in its own column, a figure that owns the full width
 * stays on the content line.
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
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = HelionType.labelSmall, color = colors.textTertiary, textAlign = textAlign)
        FigureWithUnit(
            value = value,
            unit = unit,
            valueStyle = HelionType.valueMedium,
            valueColor = valueColor ?: colors.textPrimary,
            centred = centred,
        )
    }
}

/** One cell of a [HelionStatRow]. */
data class StatCell(val label: String, val value: String, val unit: String? = null, val valueColor: Color? = null)

/**
 * Two to four figures in equal columns separated by hairline rules -- the instrument-panel
 * readout used for min/average/max, phase durations and the like. Equal weights mean no
 * figure can push into its neighbour, whatever its width.
 */
@Composable
fun HelionStatRow(cells: List<StatCell>, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        cells.forEachIndexed { index, cell ->
            if (index > 0) VerticalRule()
            HelionStatItem(
                label = cell.label,
                value = cell.value,
                unit = cell.unit,
                valueColor = cell.valueColor,
                centred = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
