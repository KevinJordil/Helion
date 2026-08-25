package ch.kevinjordil.helion.ui.settings

import android.content.Context

/**
 * The daily step goal used as `steps`' reference axis (see
 * [ch.kevinjordil.helion.ui.quality.referenceForSteps]). A plain user preference, editable
 * in Réglages -- [DEFAULT_STEPS_GOAL] is a common everyday target offered as a starting
 * point, not a figure presented as medical guidance.
 *
 * Same "helion" preferences file [ch.kevinjordil.helion.source.ExportLocation] already
 * uses for its own small persisted values, under its own key, so this does not introduce a
 * second preferences store for one more integer.
 */
class StepsGoal(context: Context) {

    private val prefs = context.getSharedPreferences("helion", Context.MODE_PRIVATE)

    var value: Int
        get() = prefs.getInt(KEY_STEPS_GOAL, DEFAULT_STEPS_GOAL)
        set(goal) = prefs.edit().putInt(KEY_STEPS_GOAL, goal).apply()

    companion object {
        const val DEFAULT_STEPS_GOAL = 8000
        private const val KEY_STEPS_GOAL = "steps_goal"
    }
}
