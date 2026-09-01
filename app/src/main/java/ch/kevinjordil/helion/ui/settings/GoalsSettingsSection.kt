package ch.kevinjordil.helion.ui.settings

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R

/**
 * The daily step goal used as `steps`' reference axis (see [StepsGoal]'s own kdoc) --
 * Réglages' [SettingsSection.GOALS] entry. The one field here today; named plural
 * ("Objectifs") so a future second goal has an obvious home rather than forcing another
 * top-level entry.
 */
@Composable
fun GoalsSettingsSection(container: AppContainer) {
    var stepsGoalText by remember { mutableStateOf(container.stepsGoal.value.toString()) }

    SettingsFieldLabel(stringResource(R.string.steps_goal_label))
    OutlinedTextField(
        value = stepsGoalText,
        onValueChange = { text ->
            stepsGoalText = text
            // Only a valid, positive whole number is actually stored -- an in-progress
            // or empty edit must not silently reset the goal a metric comparison
            // elsewhere is reading from.
            text.toIntOrNull()?.takeIf { it > 0 }?.let { container.stepsGoal.value = it }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}
