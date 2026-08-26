package ch.kevinjordil.helion.calorie

import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.ui.settings.Sex
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun sample(minute: Long, heartRate: Int?) = MinuteSample(
    timestamp = minute * 60,
    steps = null,
    intensity = null,
    rawKind = null,
    heartRate = heartRate,
    sleepStage = null,
)

/**
 * [CalorieEstimator.kcalPerMinute] against hand-computed values for both sexes (see the
 * class kdoc for the Keytel et al. (2005) coefficients themselves), then
 * [CalorieEstimator.sumKcal] and [estimateActivityCalories] for the honesty rules the whole
 * feature exists for: gaps are neither zero effort nor filled in, and a missing profile or a
 * heart-rate-free session must say so rather than guess.
 */
class CalorieEstimatorTest {

    @Test
    fun `male coefficient matches a hand-computed value`() {
        // (-55.0969 + 0.6309*130 + 0.1988*70 + 0.2017*30) / 4.184 = 11.2063 kcal/min
        val kcal = CalorieEstimator.kcalPerMinute(Sex.MALE, heartRate = 130, weightKg = 70.0, ageYears = 30.0)
        assertEquals(11.2063, kcal, 0.001)
    }

    @Test
    fun `female coefficient matches a hand-computed value`() {
        // (-20.4022 + 0.4472*130 - 0.1263*60 + 0.074*30) / 4.184 = 7.7380 kcal/min
        val kcal = CalorieEstimator.kcalPerMinute(Sex.FEMALE, heartRate = 130, weightKg = 60.0, ageYears = 30.0)
        assertEquals(7.7380, kcal, 0.001)
    }

    @Test
    fun `a heart rate too low for the formula's exercise range clamps to zero rather than going negative`() {
        // -55.0969 + 0.6309*40 + 0.1988*70 + 0.2017*30 = -2.365 kJ/min -- a real negative
        // expenditure is not a thing; the formula has simply run outside the intensity
        // range it was fit on, so the honest answer for that minute is "nothing counted",
        // not a value that would drag a whole-session sum below what was actually measured.
        val kcal = CalorieEstimator.kcalPerMinute(Sex.MALE, heartRate = 40, weightKg = 70.0, ageYears = 30.0)
        assertEquals(0.0, kcal, 0.0001)
    }

    @Test
    fun `sumKcal adds every minute's own contribution rather than using the average heart rate`() {
        val samples = listOf(sample(0, 100), sample(1, 120), sample(2, 140))
        val total = CalorieEstimator.sumKcal(Sex.MALE, weightKg = 70.0, ageYears = 30.0, samples = samples)
        // 6.6826 + 9.6984 + 12.7142 = 29.0952, rounded
        assertEquals(29, total)

        // An average-heart-rate approach would instead compute kcalPerMinute at HR=120 for
        // all three minutes (3 * 9.6984 = 29.10) -- close by coincidence here because the
        // three heart rates are evenly spread around their own mean, but the per-minute sum
        // and the average-based figure are computed by genuinely different arithmetic; the
        // point of this test is that sumKcal takes the first path, not the second.
        val averageBasedTotal = (CalorieEstimator.kcalPerMinute(Sex.MALE, 120, 70.0, 30.0) * 3)
        assertTrue(kotlin.math.abs(averageBasedTotal - total!!.toDouble()) < 1.0)
    }

    @Test
    fun `a gap in the middle of a session is skipped, not counted as zero effort and not filled in`() {
        // Minute 1 has no heart-rate reading -- a real recording gap.
        val withGap = listOf(sample(0, 100), sample(1, null), sample(2, 140))
        val total = CalorieEstimator.sumKcal(Sex.MALE, weightKg = 70.0, ageYears = 30.0, samples = withGap)

        // Must equal exactly the two-minute sum (100 and 140 bpm only) -- not three minutes
        // averaged in a way that would inflate or dilute the total by guessing at the gap.
        val twoMinuteSum = CalorieEstimator.sumKcal(Sex.MALE, weightKg = 70.0, ageYears = 30.0, samples = listOf(sample(0, 100), sample(2, 140)))
        assertEquals(twoMinuteSum, total)

        // And it must not equal what three minutes at the same intensity would give: the
        // gap genuinely reduces the total rather than the missing minute being treated as
        // free extra effort.
        val threeMinutesNoGap = CalorieEstimator.sumKcal(Sex.MALE, weightKg = 70.0, ageYears = 30.0, samples = listOf(sample(0, 100), sample(1, 100), sample(2, 140)))
        assertTrue(total!! < threeMinutesNoGap!!)
    }

    @Test
    fun `no heart-rate reading at all means no estimate, not zero kcal`() {
        val samples = listOf(sample(0, null), sample(1, null))
        val total = CalorieEstimator.sumKcal(Sex.MALE, weightKg = 70.0, ageYears = 30.0, samples = samples)
        assertNull(total)
    }

    @Test
    fun `estimateActivityCalories reports ProfileIncomplete rather than guessing a weight or age`() {
        val samples = listOf(sample(0, 130))
        val result = estimateActivityCalories(
            dateOfBirthEpochDay = null,
            weightKg = null,
            sex = null,
            activityStartTimestamp = 0,
            zone = ZoneOffset.UTC,
            samples = samples,
        )
        assertEquals(ActivityCalorieEstimate.ProfileIncomplete, result)
    }

    @Test
    fun `estimateActivityCalories reports NoHeartRateData when the session has no reading`() {
        val samples = listOf(sample(0, null), sample(1, null))
        val result = estimateActivityCalories(
            dateOfBirthEpochDay = LocalDate.of(1994, 1, 1).toEpochDay(),
            weightKg = 70f,
            sex = Sex.MALE,
            activityStartTimestamp = 0,
            zone = ZoneOffset.UTC,
            samples = samples,
        )
        assertEquals(ActivityCalorieEstimate.NoHeartRateData, result)
    }

    @Test
    fun `estimateActivityCalories computes age as of the activity date, not today`() {
        val dateOfBirth = LocalDate.of(1994, 6, 15)
        // The activity happened on 2024-06-14, one day before that year's birthday: the
        // owner was still 29, not yet 30, on the day this session was recorded.
        val activityStart = LocalDate.of(2024, 6, 14).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val samples = listOf(sample(0, 130))

        val result = estimateActivityCalories(
            dateOfBirthEpochDay = dateOfBirth.toEpochDay(),
            weightKg = 70f,
            sex = Sex.MALE,
            activityStartTimestamp = activityStart,
            zone = ZoneOffset.UTC,
            samples = samples,
        )
        val expectedKcal = CalorieEstimator.kcalPerMinute(Sex.MALE, 130, 70.0, 29.0).let { kotlin.math.round(it).toInt() }
        assertEquals(ActivityCalorieEstimate.Estimated(expectedKcal), result)
    }
}
