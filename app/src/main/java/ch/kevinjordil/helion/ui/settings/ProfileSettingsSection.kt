package ch.kevinjordil.helion.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** `dd/MM/yyyy`, the same day-month-year order every other date field in the app uses (see [ch.kevinjordil.helion.ui.activity.ACTIVITY_DATETIME_FORMAT]), without a time-of-day component: a date of birth has none. */
private val DATE_OF_BIRTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

private fun parseDateOfBirth(text: String): LocalDate? = try {
    LocalDate.parse(text.trim(), DATE_OF_BIRTH_FORMAT)
} catch (e: DateTimeParseException) {
    null
}

/**
 * The owner's own inputs to [ch.kevinjordil.helion.calorie.CalorieEstimator] -- date of
 * birth, weight, sex -- Réglages' [SettingsSection.PROFILE] entry. Unchanged from before
 * this screen was split out of one long scroll: same self-saving fields, same "a blank or
 * unparsable date never silently stores one" rule.
 */
@Composable
fun ProfileSettingsSection(container: AppContainer) {
    val colors = HelionThemeTokens.colors

    var dateOfBirthText by remember {
        mutableStateOf(container.profile.dateOfBirthEpochDay?.let { DATE_OF_BIRTH_FORMAT.format(LocalDate.ofEpochDay(it)) }.orEmpty())
    }
    var weightText by remember { mutableStateOf(container.profile.weightKg?.toString().orEmpty()) }
    var selectedSex by remember { mutableStateOf(container.profile.sex) }

    SettingsFieldLabel(stringResource(R.string.profile_date_of_birth_label))
    OutlinedTextField(
        value = dateOfBirthText,
        onValueChange = { text ->
            dateOfBirthText = text
            // Same "save the moment it's valid, never a half-typed guess" rule as every
            // other self-saving field on this screen and on the activity detail screen:
            // an empty or unparsable date must never silently store a birth date, and a
            // blank field explicitly clears one that was set before.
            if (text.isBlank()) {
                container.profile.dateOfBirthEpochDay = null
            } else {
                parseDateOfBirth(text)?.let { container.profile.dateOfBirthEpochDay = it.toEpochDay() }
            }
        },
        singleLine = true,
    )
    if (dateOfBirthText.isNotBlank() && parseDateOfBirth(dateOfBirthText) == null) {
        SettingsWarning(stringResource(R.string.profile_date_of_birth_invalid))
    }

    SettingsFieldLabel(stringResource(R.string.profile_weight_label))
    OutlinedTextField(
        value = weightText,
        onValueChange = { text ->
            weightText = text
            if (text.isBlank()) {
                container.profile.weightKg = null
            } else {
                text.toFloatOrNull()?.takeIf { it > 0f }?.let { container.profile.weightKg = it }
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
    )

    SettingsFieldLabel(stringResource(R.string.profile_sex_label))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(Sex.MALE to R.string.profile_sex_male, Sex.FEMALE to R.string.profile_sex_female).forEach { (sex, labelRes) ->
            Text(
                stringResource(labelRes).uppercase(),
                style = HelionType.label,
                color = if (sex == selectedSex) colors.accentViolet else colors.textTertiary,
                modifier = Modifier.clickable {
                    selectedSex = sex
                    container.profile.sex = sex
                },
            )
        }
    }

    Text(stringResource(R.string.profile_note), style = HelionType.bodySmall, color = colors.textSecondary)
}
