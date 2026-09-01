package ch.kevinjordil.helion.ui.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.store.SportCategory
import ch.kevinjordil.helion.store.SportType
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType

/**
 * [SportType.entries] whose French label ([sportLabelRes], via [labelOf]) contains [query],
 * case-insensitively -- every entry, in the enum's own (category-grouped) order, when
 * [query] is blank. Matches against the French label, never [SportType.name] or [sportSlug]:
 * the owner types what he reads on screen, not an English identifier he has never seen. A
 * plain function rather than something inline inside [SportPicker] so this can be exercised
 * in a unit test without a Compose environment -- [labelOf] is handed in for exactly that
 * reason, decoupled from `stringResource`.
 */
fun filterSports(query: String, labelOf: (SportType) -> String): List<SportType> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return SportType.entries.toList()
    return SportType.entries.filter { labelOf(it).lowercase().contains(needle) }
}

/**
 * The sport picker, shared by the activity detail screen, manual creation and slot editing --
 * the one place [SportType] is turned into a picker so the three never grow three different
 * pickers with three different label sets.
 *
 * [selected] is nullable ([Activity.sport][ch.kevinjordil.helion.store.Activity.sport] can be
 * unset -- see that field's own kdoc): with nothing selected this shows a neutral placeholder
 * rather than defaulting the label row to any one sport. Tapping the current selection (or
 * the placeholder) expands a search field plus the full catalogue grouped under
 * [sportCategoryLabelRes], so fifty-seven names stay navigable instead of a single
 * unscrollable list -- typing filters every category at once, and an empty category is
 * simply not shown.
 */
@Composable
fun SportPicker(selected: SportType?, onSelect: (SportType) -> Unit, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    Column(modifier = modifier) {
        Text(
            (selected?.let { stringResource(sportLabelRes(it)) } ?: stringResource(R.string.sport_none)).uppercase(),
            style = HelionType.label,
            color = if (selected != null) colors.accentViolet else colors.textTertiary,
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
        )

        if (expanded) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.sport_search_placeholder)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            val filtered = remember(query) {
                filterSports(query) { sport -> context.getString(sportLabelRes(sport)) }
            }
            val byCategory = remember(filtered) { filtered.groupBy { it.category } }

            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                SportCategory.entries.forEach { category ->
                    val inCategory = byCategory[category].orEmpty()
                    if (inCategory.isNotEmpty()) {
                        item(key = "category-${category.name}") {
                            Text(
                                stringResource(sportCategoryLabelRes(category)).uppercase(),
                                style = HelionType.labelSmall,
                                color = colors.textTertiary,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(inCategory, key = { it.name }) { sport ->
                            Text(
                                stringResource(sportLabelRes(sport)),
                                style = HelionType.body,
                                color = if (sport == selected) colors.accentViolet else colors.textPrimary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelect(sport)
                                        expanded = false
                                        query = ""
                                    }
                                    .padding(vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
