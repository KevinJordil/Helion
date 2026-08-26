package ch.kevinjordil.helion.calorie

import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.ui.settings.Profile
import ch.kevinjordil.helion.ui.settings.Sex
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Estimates energy expenditure from heart rate, using the sex-specific regression
 * equations from Keytel LR, Goedecke JH, Noakes TD, Hiiloskorpi H, Laukkanen R,
 * Van der Merwe L, Lambert EV. "Prediction of energy expenditure from heart rate
 * monitoring during submaximal exercise." Journal of Sports Sciences. 2005;23(3):289-297.
 * Coefficients as transcribed there -- check them against the paper before trusting them
 * here.
 *
 * Men:   EE (kJ/min) = -55.0969 + 0.6309*HR + 0.1988*weight(kg) + 0.2017*age(years)
 * Women: EE (kJ/min) = -20.4022 + 0.4472*HR - 0.1263*weight(kg) + 0.074*age(years)
 *
 * The paper's own output is kJ/min; it is converted to kcal/min here with the standard
 * 4.184 kJ/kcal factor so the figure reads the same as every other calorie count the owner
 * sees elsewhere (Strava included).
 *
 * These equations were fit on a specific population of moderately fit young-to-middle-aged
 * adults exercising at submaximal intensity. Keytel et al. report roughly 15-20% error for
 * an average subject in that population, and considerably more at the extremes -- highly
 * trained, untrained, or an atypical body composition. Nothing here corrects for that: it
 * is not something a formula this simple can know about the person it is applied to. It is
 * far better at comparing two of the owner's own sessions than at being right in absolute
 * terms.
 */
object CalorieEstimator {

    private const val KJ_PER_KCAL = 4.184

    /**
     * kcal burned in one minute at [heartRate] bpm, for a person of [weightKg] and
     * [ageYears]. Clamped at zero: below the formula's effective exercise-intensity range
     * the raw regression goes negative, which is not a real "negative expenditure" -- it
     * just means the equation has nothing useful to say about that minute, so it
     * contributes nothing to the total rather than pulling it down.
     */
    fun kcalPerMinute(sex: Sex, heartRate: Int, weightKg: Double, ageYears: Double): Double {
        val kjPerMinute = when (sex) {
            Sex.MALE -> -55.0969 + 0.6309 * heartRate + 0.1988 * weightKg + 0.2017 * ageYears
            Sex.FEMALE -> -20.4022 + 0.4472 * heartRate - 0.1263 * weightKg + 0.074 * ageYears
        }
        return (kjPerMinute / KJ_PER_KCAL).coerceAtLeast(0.0)
    }

    /**
     * Sums [kcalPerMinute] over every [MinuteSample] that actually carries a heart-rate
     * reading. A minute with none -- a gap in the strap's own per-minute recording -- is
     * skipped entirely rather than treated as zero effort (which would understate a
     * genuinely active gap) or filled by interpolation (which would invent a reading the
     * device never took): there is no honest way to know what happened during a minute
     * nothing was recorded for, so it is left out of the sum altogether, the same choice
     * [ch.kevinjordil.helion.export.writeTcx] already makes for that minute's trackpoint.
     *
     * Returns null when [samples] carries no heart-rate reading at all -- the "no estimate
     * possible" case the caller must say plainly, never as a silent 0 kcal.
     */
    fun sumKcal(sex: Sex, weightKg: Double, ageYears: Double, samples: List<MinuteSample>): Int? {
        val withHeartRate = samples.mapNotNull { sample -> sample.heartRate?.let { it to sample } }
        if (withHeartRate.isEmpty()) return null
        val total = withHeartRate.sumOf { (heartRate, _) -> kcalPerMinute(sex, heartRate, weightKg, ageYears) }
        return total.roundToInt()
    }
}

/** The outcome of trying to estimate one activity's calories -- see [estimateActivityCalories]. */
sealed class ActivityCalorieEstimate {
    /** A real estimate, in kcal. Always labelled "estimé" wherever it is shown -- see [CalorieEstimator]'s own kdoc for why. */
    data class Estimated(val kcal: Int) : ActivityCalorieEstimate()

    /** [Profile.isComplete] is false: date of birth, weight or sex is missing. Never guessed. */
    object ProfileIncomplete : ActivityCalorieEstimate()

    /** The activity's minutes carry no heart-rate reading at all. */
    object NoHeartRateData : ActivityCalorieEstimate()
}

/**
 * The pure version of [estimateActivityCalories]: takes the profile fields directly rather
 * than a [Profile], so the "does not guess a missing field" and "age as of the activity
 * date, not today" logic can be tested without any Android storage plumbing involved. Age
 * is computed as of [activityStartTimestamp] (in [zone]), not "today" -- an old activity is
 * estimated against the age the owner actually was when it happened.
 */
fun estimateActivityCalories(
    dateOfBirthEpochDay: Long?,
    weightKg: Float?,
    sex: Sex?,
    activityStartTimestamp: Long,
    zone: ZoneId,
    samples: List<MinuteSample>,
): ActivityCalorieEstimate {
    if (dateOfBirthEpochDay == null || weightKg == null || sex == null) return ActivityCalorieEstimate.ProfileIncomplete

    val dateOfBirth = LocalDate.ofEpochDay(dateOfBirthEpochDay)
    val activityDate = Instant.ofEpochSecond(activityStartTimestamp).atZone(zone).toLocalDate()
    val ageYears = Period.between(dateOfBirth, activityDate).years.coerceAtLeast(0)

    val kcal = CalorieEstimator.sumKcal(sex, weightKg.toDouble(), ageYears.toDouble(), samples)
        ?: return ActivityCalorieEstimate.NoHeartRateData
    return ActivityCalorieEstimate.Estimated(kcal)
}

/** Ties a real [Profile] and an activity's own [samples] together -- see the overload above for the actual logic. */
fun estimateActivityCalories(
    profile: Profile,
    activityStartTimestamp: Long,
    zone: ZoneId,
    samples: List<MinuteSample>,
): ActivityCalorieEstimate = estimateActivityCalories(
    profile.dateOfBirthEpochDay,
    profile.weightKg,
    profile.sex,
    activityStartTimestamp,
    zone,
    samples,
)
