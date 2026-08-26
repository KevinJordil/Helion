package ch.kevinjordil.helion.ui.activity

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.customserver.CustomServerFailureReason
import ch.kevinjordil.helion.store.ActivityStatus
import ch.kevinjordil.helion.store.PublicationState
import ch.kevinjordil.helion.store.SportType
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

/**
 * The French label for a custom-server [PublicationState] -- there is no upload job to be
 * "en cours" for this target (see [ch.kevinjordil.helion.customserver.CustomServerPublisher]'s
 * own kdoc): [PublicationState.UPLOADING] is never actually produced for
 * [ch.kevinjordil.helion.store.PublicationTarget.CUSTOM_SERVER], but the `when` still
 * covers it rather than silently falling through, in case that ever changes.
 * [PublicationState.ALREADY_KNOWN] gets its own distinct label from [PublicationState.PUBLISHED]
 * -- "already had it, sent nothing on" is worth telling apart from "just accepted".
 */
fun customServerStateLabelRes(state: PublicationState): Int = when (state) {
    PublicationState.PENDING, PublicationState.UPLOADING -> R.string.custom_server_state_pending
    PublicationState.PUBLISHED -> R.string.custom_server_state_sent
    PublicationState.ALREADY_KNOWN -> R.string.custom_server_state_already_known
    PublicationState.FAILED -> R.string.custom_server_state_failed
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
