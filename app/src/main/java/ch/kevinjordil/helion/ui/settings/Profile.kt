package ch.kevinjordil.helion.ui.settings

import android.content.Context

/**
 * The two sexes the Keytel et al. (2005) heart-rate calorie equations are calibrated for --
 * see [ch.kevinjordil.helion.calorie.CalorieEstimator]'s own kdoc. There is no third option
 * because the published equations themselves only give coefficients for these two; adding
 * one here would just be a choice this app has no formula to back.
 */
enum class Sex { MALE, FEMALE }

/**
 * The owner's own inputs [ch.kevinjordil.helion.calorie.CalorieEstimator] needs: date of
 * birth, weight, and sex. Deliberately does not store height -- the Keytel equations this
 * app uses do not take it as a term, and a health field nothing ever reads is exactly the
 * quiet data hoarding this project avoids.
 *
 * Same "helion" preferences file [StepsGoal] and [ch.kevinjordil.helion.source.ExportLocation]
 * already use, under their own keys. This is health data like everything else Helion
 * stores: it stays on the device, and it only ever leaves as part of a calorie figure the
 * owner chooses to publish (see [ch.kevinjordil.helion.export.writeTcx]).
 */
class Profile(context: Context) {

    private val prefs = context.getSharedPreferences("helion", Context.MODE_PRIVATE)

    /** Days since the epoch (`java.time.LocalDate.toEpochDay`), or null if never set. */
    var dateOfBirthEpochDay: Long?
        get() = if (prefs.contains(KEY_DOB)) prefs.getLong(KEY_DOB, 0L) else null
        set(value) {
            val edit = prefs.edit()
            if (value == null) edit.remove(KEY_DOB) else edit.putLong(KEY_DOB, value)
            edit.apply()
        }

    var weightKg: Float?
        get() = if (prefs.contains(KEY_WEIGHT)) prefs.getFloat(KEY_WEIGHT, 0f) else null
        set(value) {
            val edit = prefs.edit()
            if (value == null) edit.remove(KEY_WEIGHT) else edit.putFloat(KEY_WEIGHT, value)
            edit.apply()
        }

    var sex: Sex?
        get() = prefs.getString(KEY_SEX, null)?.let { raw -> Sex.entries.firstOrNull { it.name == raw } }
        set(value) {
            val edit = prefs.edit()
            if (value == null) edit.remove(KEY_SEX) else edit.putString(KEY_SEX, value.name)
            edit.apply()
        }

    /** True once every field a calorie estimate needs is actually set. */
    val isComplete: Boolean
        get() = dateOfBirthEpochDay != null && weightKg != null && sex != null

    companion object {
        private const val KEY_DOB = "profile_date_of_birth_epoch_day"
        private const val KEY_WEIGHT = "profile_weight_kg"
        private const val KEY_SEX = "profile_sex"
    }
}
