package ch.kevinjordil.helion.ui.activity

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.store.ActivityStatus
import ch.kevinjordil.helion.store.PublicationState
import ch.kevinjordil.helion.store.SportType
import ch.kevinjordil.helion.strava.PublicationFailureReason
import ch.kevinjordil.helion.strava.StravaAuthFailure
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** The short French label for [sport], shown everywhere a sport is picked or displayed. */
fun sportLabelRes(sport: SportType): Int = when (sport) {
    SportType.BADMINTON -> R.string.sport_badminton
    SportType.RUNNING -> R.string.sport_running
    SportType.CYCLING -> R.string.sport_cycling
    SportType.WALKING -> R.string.sport_walking
    SportType.SWIMMING -> R.string.sport_swimming
    SportType.OTHER -> R.string.sport_other
}

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

/** The French label for a Strava [PublicationState], shown on the activity detail screen. */
fun publicationStateLabelRes(state: PublicationState): Int = when (state) {
    PublicationState.PENDING -> R.string.strava_state_pending
    PublicationState.UPLOADING -> R.string.strava_state_uploading
    PublicationState.PUBLISHED -> R.string.strava_state_published
    PublicationState.FAILED -> R.string.strava_state_failed
}

/**
 * The French explanation for a stored [ch.kevinjordil.helion.store.Publication.lastError]
 * reason code (see [PublicationFailureReason]). Falls back to the generic remote-error
 * message for anything unrecognised, so a future reason code added elsewhere never renders
 * as a blank string.
 */
fun publicationFailureReasonRes(reason: String?): Int = when (reason) {
    PublicationFailureReason.NEVER_CONNECTED -> R.string.strava_reason_never_connected
    PublicationFailureReason.AUTH_EXPIRED -> R.string.strava_reason_auth_expired
    PublicationFailureReason.NOT_CONFIGURED -> R.string.strava_reason_not_configured
    PublicationFailureReason.NETWORK_ERROR -> R.string.strava_reason_network_error
    else -> R.string.strava_reason_remote_error
}

/**
 * The French sentence for a failed OAuth attempt itself (as opposed to
 * [publicationFailureReasonRes], which explains a failed *publish*), with Strava's own
 * [StravaAuthFailure.detail] spliced into the `%1$s` placeholder every variant but
 * [StravaAuthFailure.NotConfigured] carries -- see `strings.xml`'s `strava_auth_*` entries.
 */
fun stravaAuthFailureRes(failure: StravaAuthFailure): Int = when (failure) {
    is StravaAuthFailure.Declined -> R.string.strava_auth_declined
    is StravaAuthFailure.Rejected -> R.string.strava_auth_rejected
    is StravaAuthFailure.NetworkError -> R.string.strava_auth_network_error
    StravaAuthFailure.NotConfigured -> R.string.strava_reason_not_configured
}

/** The `stringResource` format args for [stravaAuthFailureRes] -- empty for the one variant with no placeholder. */
fun stravaAuthFailureArgs(failure: StravaAuthFailure): List<Any> = when (failure) {
    StravaAuthFailure.NotConfigured -> emptyList()
    else -> listOf(failure.detail)
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
