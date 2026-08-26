package ch.kevinjordil.helion.strava

import ch.kevinjordil.helion.calorie.ActivityCalorieEstimate
import ch.kevinjordil.helion.calorie.estimateActivityCalories
import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.ui.settings.Profile
import java.time.ZoneId

/**
 * Shared, target-agnostic pieces of publishing an [Activity] somewhere -- used by both
 * [StravaPublisher] and [ch.kevinjordil.helion.customserver.CustomServerPublisher], so a
 * repeat send always carries the exact same identity and name regardless of where it goes.
 */

/**
 * A stable, per-activity id every send target can use for its own duplicate detection.
 * Strava treats this as its own de-duplication key (see [StravaApi.createUpload]'s kdoc);
 * a custom server is expected to do the same with its own `external_id` field -- see
 * `README.md`'s custom-server contract.
 */
fun externalIdFor(activityId: Long): String = "helion-activity-$activityId"

/** The name shown for [activity] wherever a target needs a title -- never blank. */
fun activityDisplayName(activity: Activity): String = activity.title?.takeIf { it.isNotBlank() } ?: "Helion"

/**
 * The kcal figure to embed in an export for [activity], or null when there is no [profile]
 * to estimate from or no heart rate to estimate with -- never a guessed placeholder value.
 */
fun calorieEstimateFor(profile: Profile?, activity: Activity, zone: ZoneId, samples: List<MinuteSample>): Int? {
    val ownerProfile = profile ?: return null
    return when (val estimate = estimateActivityCalories(ownerProfile, activity.startTimestamp, zone, samples)) {
        is ActivityCalorieEstimate.Estimated -> estimate.kcal
        ActivityCalorieEstimate.ProfileIncomplete, ActivityCalorieEstimate.NoHeartRateData -> null
    }
}
