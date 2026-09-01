package ch.kevinjordil.helion.ui.activity

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.customserver.CustomServerFailureReason
import ch.kevinjordil.helion.store.ActivityStatus
import ch.kevinjordil.helion.store.PublicationState
import ch.kevinjordil.helion.store.SportCategory
import ch.kevinjordil.helion.store.SportType
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** The short French label for [sport], shown everywhere a sport is picked or displayed. */
fun sportLabelRes(sport: SportType): Int = when (sport) {
    SportType.RIDE -> R.string.sport_ride
    SportType.E_BIKE_RIDE -> R.string.sport_e_bike_ride
    SportType.E_MOUNTAIN_BIKE_RIDE -> R.string.sport_e_mountain_bike_ride
    SportType.GRAVEL_RIDE -> R.string.sport_gravel_ride
    SportType.HANDCYCLE -> R.string.sport_handcycle
    SportType.MOUNTAIN_BIKE_RIDE -> R.string.sport_mountain_bike_ride
    SportType.VELOMOBILE -> R.string.sport_velomobile

    SportType.HIKE -> R.string.sport_hike
    SportType.RUN -> R.string.sport_run
    SportType.TRAIL_RUN -> R.string.sport_trail_run
    SportType.WALK -> R.string.sport_walk

    SportType.CANOEING -> R.string.sport_canoeing
    SportType.KAYAKING -> R.string.sport_kayaking
    SportType.KITESURF -> R.string.sport_kitesurf
    SportType.ROWING -> R.string.sport_rowing
    SportType.SAIL -> R.string.sport_sail
    SportType.STAND_UP_PADDLING -> R.string.sport_stand_up_paddling
    SportType.SURFING -> R.string.sport_surfing
    SportType.SWIM -> R.string.sport_swim
    SportType.WINDSURF -> R.string.sport_windsurf

    SportType.ALPINE_SKI -> R.string.sport_alpine_ski
    SportType.BACKCOUNTRY_SKI -> R.string.sport_backcountry_ski
    SportType.ICE_SKATE -> R.string.sport_ice_skate
    SportType.NORDIC_SKI -> R.string.sport_nordic_ski
    SportType.ROLLER_SKI -> R.string.sport_roller_ski
    SportType.SNOWBOARD -> R.string.sport_snowboard
    SportType.SNOWSHOE -> R.string.sport_snowshoe

    SportType.BADMINTON -> R.string.sport_badminton
    SportType.PADEL -> R.string.sport_padel
    SportType.PICKLEBALL -> R.string.sport_pickleball
    SportType.RACQUETBALL -> R.string.sport_racquetball
    SportType.SQUASH -> R.string.sport_squash
    SportType.TABLE_TENNIS -> R.string.sport_table_tennis
    SportType.TENNIS -> R.string.sport_tennis

    SportType.CROSSFIT -> R.string.sport_crossfit
    SportType.DANCE -> R.string.sport_dance
    SportType.ELLIPTICAL -> R.string.sport_elliptical
    SportType.HIGH_INTENSITY_INTERVAL_TRAINING -> R.string.sport_high_intensity_interval_training
    SportType.PILATES -> R.string.sport_pilates
    SportType.PHYSICAL_THERAPY -> R.string.sport_physical_therapy
    SportType.STAIR_STEPPER -> R.string.sport_stair_stepper
    SportType.VIRTUAL_RIDE -> R.string.sport_virtual_ride
    SportType.VIRTUAL_ROW -> R.string.sport_virtual_row
    SportType.VIRTUAL_RUN -> R.string.sport_virtual_run
    SportType.WEIGHT_TRAINING -> R.string.sport_weight_training
    SportType.WORKOUT -> R.string.sport_workout
    SportType.YOGA -> R.string.sport_yoga

    SportType.BASKETBALL -> R.string.sport_basketball
    SportType.CRICKET -> R.string.sport_cricket
    SportType.SOCCER -> R.string.sport_soccer
    SportType.VOLLEYBALL -> R.string.sport_volleyball

    SportType.GOLF -> R.string.sport_golf
    SportType.INLINE_SKATE -> R.string.sport_inline_skate
    SportType.ROCK_CLIMBING -> R.string.sport_rock_climbing
    SportType.SKATEBOARD -> R.string.sport_skateboard
    SportType.WHEELCHAIR -> R.string.sport_wheelchair
    SportType.MOTORCYCLING -> R.string.sport_motorcycling
}

/** The French label for [category], shown as a heading in [SportPicker]. */
fun sportCategoryLabelRes(category: SportCategory): Int = when (category) {
    SportCategory.CYCLING -> R.string.sport_category_cycling
    SportCategory.RUNNING_WALKING -> R.string.sport_category_running_walking
    SportCategory.WATER -> R.string.sport_category_water
    SportCategory.SNOW_ICE -> R.string.sport_category_snow_ice
    SportCategory.RACKET -> R.string.sport_category_racket
    SportCategory.INDOOR_FITNESS -> R.string.sport_category_indoor_fitness
    SportCategory.TEAM -> R.string.sport_category_team
    SportCategory.OTHER -> R.string.sport_category_other
}

/**
 * [sportLabelRes]'s label for [sport], or [R.string.sport_none] when it is null -- the
 * activity list's own display of [ch.kevinjordil.helion.store.Activity.sport], which a
 * freely detected candidate can genuinely be (see that field's own kdoc).
 */
@Composable
fun sportOrNoneLabel(sport: SportType?): String =
    sport?.let { stringResource(sportLabelRes(it)) } ?: stringResource(R.string.sport_none)

/** The French label for [status], shown on the list and the detail screen alike. */
fun statusLabelRes(status: ActivityStatus): Int = when (status) {
    ActivityStatus.CANDIDATE -> R.string.activity_status_candidate
    ActivityStatus.CONFIRMED -> R.string.activity_status_confirmed
    ActivityStatus.PUBLISHED -> R.string.activity_status_published
    ActivityStatus.DISMISSED -> R.string.activity_status_dismissed
}

/**
 * Whether [status] is a candidate awaiting the owner's verdict -- the one state that must
 * always be visually distinct from a decided one (see the module's own brief). This is the
 * only status drawn in [ch.kevinjordil.helion.ui.theme.HelionColors.accentAmber]: amber
 * means "needs your attention" everywhere else in the app, and an unreviewed candidate is
 * exactly that.
 */
fun needsAttention(status: ActivityStatus): Boolean = status == ActivityStatus.CANDIDATE

/**
 * The French label for a custom-server [PublicationState] -- there is no upload job to be
 * "en cours" for this target (see [ch.kevinjordil.helion.customserver.CustomServerPublisher]'s
 * own kdoc): [PublicationState.UPLOADING] is never actually produced for
 * [ch.kevinjordil.helion.store.PublicationTarget.CUSTOM_SERVER], but the `when` still
 * covers it rather than silently falling through, in case that ever changes.
 * [PublicationState.ALREADY_KNOWN] gets its own distinct label from [PublicationState.PUBLISHED]
 * -- "already had it, sent nothing on" is worth telling apart from "just accepted".
 * [PublicationState.REMOVED] is never actually produced for
 * [ch.kevinjordil.helion.store.PublicationTarget.CUSTOM_SERVER] either (see that state's own
 * kdoc -- it is a Health Connect-only outcome), but the `when` still covers it for the same
 * "never silently fall through" reason as [PublicationState.UPLOADING].
 */
fun customServerStateLabelRes(state: PublicationState): Int = when (state) {
    PublicationState.PENDING, PublicationState.UPLOADING -> R.string.custom_server_state_pending
    PublicationState.PUBLISHED -> R.string.custom_server_state_sent
    PublicationState.ALREADY_KNOWN -> R.string.custom_server_state_already_known
    PublicationState.FAILED, PublicationState.REMOVED -> R.string.custom_server_state_failed
}

/**
 * The French explanation for a stored custom-server
 * [ch.kevinjordil.helion.store.Publication.lastError] reason code (see
 * [CustomServerFailureReason]). Falls back to the generic remote-error message for anything
 * unrecognised, same reasoning as [publicationFailureReasonRes].
 */
fun customServerFailureReasonRes(reason: String?): Int = when (reason) {
    CustomServerFailureReason.NOT_CONFIGURED -> R.string.custom_server_reason_not_configured
    CustomServerFailureReason.INVALID_URL -> R.string.custom_server_reason_invalid_url
    CustomServerFailureReason.PLAIN_HTTP_NOT_CONFIRMED -> R.string.custom_server_reason_plain_http_not_confirmed
    CustomServerFailureReason.NETWORK_ERROR -> R.string.custom_server_reason_network_error
    CustomServerFailureReason.UNAUTHORIZED -> R.string.custom_server_reason_unauthorized
    CustomServerFailureReason.NO_SPORT -> R.string.custom_server_reason_no_sport
    else -> R.string.custom_server_reason_remote_error
}

/**
 * The `stringResource` format args for [customServerFailureReasonRes] -- [detail] fills the
 * one `%1$s` placeholder every variant carries except [CustomServerFailureReason.NOT_CONFIGURED]
 * and [CustomServerFailureReason.PLAIN_HTTP_NOT_CONFIRMED], which are decided locally before
 * any request ever reached the server and already have a fixed, complete French sentence.
 */
fun customServerFailureReasonArgs(reason: String?, detail: String?): List<Any> = when (reason) {
    CustomServerFailureReason.NOT_CONFIGURED,
    CustomServerFailureReason.PLAIN_HTTP_NOT_CONFIRMED,
    CustomServerFailureReason.NO_SPORT,
    -> emptyList()
    CustomServerFailureReason.INVALID_URL -> emptyList()
    else -> listOf(detail ?: "?")
}

/**
 * "1 h 30" / "45 min": [seconds] rendered as a human duration, hours only shown once there
 * is at least one whole hour to show. Reused by the list, the detail screen and the
 * day-timeline's live selection readout, so the three never drift into different phrasing.
 */
@Composable
fun activityDurationText(seconds: Long): String {
    val totalMinutes = (seconds / 60).coerceAtLeast(0)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) {
        stringResource(R.string.activity_duration_hours_minutes, hours, minutes)
    } else {
        stringResource(R.string.activity_duration_minutes, minutes)
    }
}

/** The single date/time format the activity detail screen's start and end fields both use. */
val ACTIVITY_DATETIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

/** Parses [text] as `dd/MM/yyyy HH:mm` in [zone]; null for anything that does not parse. */
fun parseActivityDateTime(text: String, zone: ZoneId): Long? = try {
    LocalDateTime.parse(text.trim(), ACTIVITY_DATETIME_FORMAT).atZone(zone).toEpochSecond()
} catch (e: DateTimeParseException) {
    null
}

/** The inverse of [parseActivityDateTime], for pre-filling a field from a stored timestamp. */
fun formatActivityDateTime(epochSeconds: Long, zone: ZoneId): String =
    ACTIVITY_DATETIME_FORMAT.format(Instant.ofEpochSecond(epochSeconds).atZone(zone))
