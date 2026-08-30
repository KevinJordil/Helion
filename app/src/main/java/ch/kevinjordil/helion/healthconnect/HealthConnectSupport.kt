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
 * [SportType] to Health Connect's own `EXERCISE_TYPE_*` vocabulary.
 *
 * [SportType.BADMINTON] has an exact match ([ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON])
 * -- the one sport this app was built around lands precisely, not in a fallback bucket.
 *
 * [SportType.CYCLING] maps to [ExerciseSessionRecord.EXERCISE_TYPE_BIKING]: Health Connect
 * has no plain "cycling" constant, only that and its stationary-bike counterpart, and this
 * device never distinguishes an indoor trainer session from a road ride.
 *
 * [SportType.SWIMMING] maps to [ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL]: Health
 * Connect splits swimming into open-water and pool, a distinction this device's own
 * `SportType` does not carry at all. Pool is the more common setting for recreational
 * swimming and the safer default of the two -- open water implies a GPS route this app
 * never has -- but this is a genuine loss of information, the same kind TCX export already
 * accepts for the same sport (see [ch.kevinjordil.helion.export.tcxSport]'s own kdoc).
 *
 * [SportType.OTHER] -- and nothing else, since every other constant above has a real
 * match -- falls back to [ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT].
 */
fun healthConnectExerciseType(sport: SportType): Int = when (sport) {
    SportType.BADMINTON -> ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON
    SportType.RUNNING -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
    SportType.CYCLING -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
    SportType.WALKING -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
    SportType.SWIMMING -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
    SportType.OTHER -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
}
