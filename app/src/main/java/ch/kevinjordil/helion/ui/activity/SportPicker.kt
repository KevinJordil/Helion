package ch.kevinjordil.helion.ui.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.store.SportType
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType

/**
 * A row of tappable sport labels, shared by the activity detail screen, manual creation and
 * slot editing -- the one place [SportType] is turned into a picker so the three never grow
 * three different pickers with three different label sets.
 */
@Composable
fun SportPicker(selected: SportType, onSelect: (SportType) -> Unit, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    LazyRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(SportType.entries.toList()) { sport ->
            Text(
                stringResource(sportLabelRes(sport)).uppercase(),
                style = HelionType.label,
                color = if (sport == selected) colors.accentViolet else colors.textTertiary,
                modifier = Modifier
                    .clickable { onSelect(sport) }
                    .padding(vertical = 4.dp),
            )
        }
    }
}
