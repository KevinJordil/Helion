package ch.kevinjordil.helion.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import ch.kevinjordil.helion.store.SportType

/**
 * Whether Health Connect can be written to at all on this phone. It is a separate system
 * component (its own app on API 26-33; folded into the OS itself on newer builds), not a
 * library this app bundles -- [HealthConnectClient.getSdkStatus] is the only way to tell
 * these apart from a real permission problem, and every caller (Réglages, the exporter
 * itself) must degrade the same way for either: writing is simply off, never a crash.
 */
sealed interface HealthConnectAvailability {
    data object Available : HealthConnectAvailability

    /** Not installed at all -- Réglages should say so plainly, not report a generic failure. */
    data object NotInstalled : HealthConnectAvailability

    /** Installed but too old for the record types this app writes. */
    data object UpdateRequired : HealthConnectAvailability
}

/** Reads the real SDK status. See [HealthConnectAvailability]'s own kdoc. */
fun healthConnectAvailability(context: Context): HealthConnectAvailability =
    when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.Available
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.UpdateRequired
        else -> HealthConnectAvailability.NotInstalled
    }

/**
 * Health permission strings this app ever requests -- one read-free write permission per
 * record type it produces. Kept in one place so the manifest's own `<uses-permission>`
 * list (`AndroidManifest.xml`), the runtime request in Réglages, and
 * [ch.kevinjordil.helion.healthconnect.HealthConnectExporter]'s own precondition all read
 * the same set rather than three lists that could drift apart.
 */
val HEALTH_CONNECT_PERMISSIONS: Set<String> = setOf(
    HealthPermission.getWritePermission(SleepSessionRecord::class),
    HealthPermission.getWritePermission(ExerciseSessionRecord::class),
    HealthPermission.getWritePermission(HeartRateRecord::class),
    HealthPermission.getWritePermission(StepsRecord::class),
    HealthPermission.getWritePermission(HeartRateVariabilityRmssdRecord::class),
    HealthPermission.getWritePermission(OxygenSaturationRecord::class),
    HealthPermission.getWritePermission(SkinTemperatureRecord::class),
    HealthPermission.getWritePermission(RespiratoryRateRecord::class),
)

/**
 * The strap Helion's own archive comes from, attached to every record this app writes so
 * Health Connect (and whatever reads it, e.g. Samsung Health) can tell it apart from data
 * some other source contributed. [Device.TYPE_FITNESS_BAND] is the closest of Health
 * Connect's own device types to a wrist strap -- there is no more specific "smart strap"
 * constant.
 */
val HELION_DEVICE: Device = Device(
    type = Device.TYPE_FITNESS_BAND,
    manufacturer = "Amazfit",
    model = "Helio Strap",
)

/**
 * [Metadata] for one record, tagged with [clientRecordId] (see this file's own kdoc on the
 * scheme) and a version derived from [now] (Unix seconds) rather than a fixed constant:
 * Health Connect only lets a later insert with the same client record id actually replace
 * an earlier one when its version is at least as high, and this app has no other durable
 * counter to bump per record. Real wall-clock time is monotonic across process restarts in
 * a way an in-memory counter is not, so a later export of the same id -- whether the
 * content changed (the owner edited an activity's title after it was already exported) or
 * not (a plain re-run) -- always carries a version at least as high as the one already
 * stored, which is exactly what makes either case safe: a genuine edit is picked up, and an
 * unchanged re-export still resolves to identical content either way.
 *
 * Recording method is always [Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED] (via
 * [Metadata.autoRecorded]): every record this app writes is the strap's own archived
 * reading or the owner's already-confirmed activity, never something typed in live.
 */
fun healthConnectMetadata(clientRecordId: String, now: Long): Metadata =
    Metadata.autoRecorded(device = HELION_DEVICE, clientRecordId = clientRecordId, clientRecordVersion = now)

// Every clientRecordId this app ever writes follows "helion-<kind>-<id>", the same
// "helion-activity-<id>" shape ch.kevinjordil.helion.export.externalIdFor already uses for
// the custom-server target -- one stable id per logical record, reused across the exact
// same range on a repeat export.

/** The exercise session and its own heart-rate series both key off this -- see [ch.kevinjordil.helion.export.externalIdFor]. */
fun healthConnectExerciseHeartRateClientId(activityId: Long): String = "helion-activity-$activityId-heart-rate"

fun healthConnectSleepSessionClientId(sessionEnd: Long): String = "helion-sleep-$sessionEnd"

fun healthConnectDailyHeartRateClientId(epochDay: Long): String = "helion-heart-rate-$epochDay"

fun healthConnectDailyStepsClientId(epochDay: Long): String = "helion-steps-$epochDay"

fun healthConnectHrvClientId(timestamp: Long): String = "helion-hrv-$timestamp"

fun healthConnectSpo2ClientId(timestamp: Long): String = "helion-spo2-$timestamp"

fun healthConnectSkinTemperatureClientId(timestamp: Long): String = "helion-skin-temperature-$timestamp"

fun healthConnectRespiratoryRateClientId(timestamp: Long): String = "helion-respiratory-rate-$timestamp"

/**
 * [SportType] to Health Connect's own `EXERCISE_TYPE_*` vocabulary (SDK 1.1.0). Most of
 * Strava's fifty-six types land on a real or reasonably-equivalent Health Connect constant
 * (the same "close enough to not be a loss" call this file already made for
 * [ExerciseSessionRecord.EXERCISE_TYPE_BIKING] and [ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL]
 * before this catalogue existed -- pool over open-water swimming stays the safer default of
 * the two, since open water implies a GPS route this app never has).
 *
 * Seven sports have no equivalent at all in Health Connect's vocabulary and fall back to the
 * generic [ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT]: [SportType.KITESURF],
 * [SportType.WINDSURF] (no kite- or wind-surfing constant, only plain [ExerciseSessionRecord.EXERCISE_TYPE_SURFING],
 * which would misrepresent the equipment), [SportType.ROLLER_SKI] (no dryland/roller-ski
 * constant, and the snow [ExerciseSessionRecord.EXERCISE_TYPE_SKIING] would misrepresent the
 * surface), [SportType.PADEL] and [SportType.PICKLEBALL] (both distinct racket sports with no
 * constant of their own -- reusing squash's or tennis's would misrepresent the sport, not
 * just lose a nuance), [SportType.PHYSICAL_THERAPY] (no rehabilitation constant;
 * [ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING] is too specific a guess at what a
 * session actually was), and [SportType.SKATEBOARD] (no skateboarding constant; the closest,
 * [ExerciseSessionRecord.EXERCISE_TYPE_SKATING], is built for inline/roller skates).
 * [SportType.WORKOUT] simply *is* the generic bucket by definition, not a fallback from
 * something more specific.
 */
fun healthConnectExerciseType(sport: SportType): Int = when (sport) {
    // -- cycling: only BIKING and BIKING_STATIONARY exist; every pedal- or motor-assisted
    // outdoor variant lands on the former, virtual/trainer riding on the latter.
    SportType.RIDE, SportType.E_BIKE_RIDE, SportType.E_MOUNTAIN_BIKE_RIDE, SportType.GRAVEL_RIDE,
    SportType.HANDCYCLE, SportType.MOUNTAIN_BIKE_RIDE, SportType.VELOMOBILE,
    -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
    SportType.VIRTUAL_RIDE -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY

    // -- running and walking --
    SportType.HIKE -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
    SportType.RUN, SportType.TRAIL_RUN -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
    SportType.VIRTUAL_RUN -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
    SportType.WALK -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING

    // -- water --
    SportType.CANOEING, SportType.KAYAKING, SportType.STAND_UP_PADDLING -> ExerciseSessionRecord.EXERCISE_TYPE_PADDLING
    SportType.ROWING -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING
    SportType.VIRTUAL_ROW -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE
    SportType.SAIL -> ExerciseSessionRecord.EXERCISE_TYPE_SAILING
    SportType.SURFING -> ExerciseSessionRecord.EXERCISE_TYPE_SURFING
    SportType.SWIM -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
    SportType.KITESURF, SportType.WINDSURF -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT

    // -- snow and ice: no distinct alpine/backcountry/nordic constants, only plain SKIING. --
    SportType.ALPINE_SKI, SportType.BACKCOUNTRY_SKI, SportType.NORDIC_SKI -> ExerciseSessionRecord.EXERCISE_TYPE_SKIING
    SportType.ICE_SKATE -> ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING
    SportType.SNOWBOARD -> ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING
    SportType.SNOWSHOE -> ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING
    SportType.ROLLER_SKI -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT

    // -- racket sports: BADMINTON is the one this app was built around, and lands
    // precisely, not in a fallback bucket. --
    SportType.BADMINTON -> ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON
    SportType.RACQUETBALL -> ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL
    SportType.SQUASH -> ExerciseSessionRecord.EXERCISE_TYPE_SQUASH
    SportType.TABLE_TENNIS -> ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS
    SportType.TENNIS -> ExerciseSessionRecord.EXERCISE_TYPE_TENNIS
    SportType.PADEL, SportType.PICKLEBALL -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT

    // -- indoor and fitness --
    SportType.CROSSFIT, SportType.HIGH_INTENSITY_INTERVAL_TRAINING ->
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING
    SportType.DANCE -> ExerciseSessionRecord.EXERCISE_TYPE_DANCING
    SportType.ELLIPTICAL -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL
    SportType.PILATES -> ExerciseSessionRecord.EXERCISE_TYPE_PILATES
    SportType.STAIR_STEPPER -> ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE
    SportType.WEIGHT_TRAINING -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
    SportType.YOGA -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA
    SportType.PHYSICAL_THERAPY -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
    // The generic bucket itself -- not a fallback from anything more specific.
    SportType.WORKOUT -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT

    // -- team sports --
    SportType.BASKETBALL -> ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL
    SportType.CRICKET -> ExerciseSessionRecord.EXERCISE_TYPE_CRICKET
    SportType.SOCCER -> ExerciseSessionRecord.EXERCISE_TYPE_SOCCER
    SportType.VOLLEYBALL -> ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL

    // -- other --
    SportType.GOLF -> ExerciseSessionRecord.EXERCISE_TYPE_GOLF
    SportType.INLINE_SKATE -> ExerciseSessionRecord.EXERCISE_TYPE_SKATING
    SportType.ROCK_CLIMBING -> ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING
    SportType.WHEELCHAIR -> ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR
    SportType.SKATEBOARD -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
}
