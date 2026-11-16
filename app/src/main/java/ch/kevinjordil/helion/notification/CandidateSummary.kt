package ch.kevinjordil.helion.notification

import android.content.Context
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.store.HeartRateSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** The French wording [candidateSummary] needs, behind an interface so it is testable on a plain JVM. */
interface CandidateStrings {
    fun today(): String
    fun yesterday(): String
    fun hoursMinutes(hours: Long, minutes: Long): String
    fun minutes(minutes: Long): String
    fun heartRate(average: Int, max: Int): String
}

/** [CandidateStrings] read from `strings.xml`. */
class AndroidCandidateStrings(private val context: Context) : CandidateStrings {
    override fun today() = context.getString(R.string.notification_day_today)
    override fun yesterday() = context.getString(R.string.notification_day_yesterday)
    override fun hoursMinutes(hours: Long, minutes: Long) = context.getString(R.string.activity_duration_hours_minutes, hours, minutes)
    override fun minutes(minutes: Long) = context.getString(R.string.activity_duration_minutes, minutes)
    override fun heartRate(average: Int, max: Int) = context.getString(R.string.notification_heart_rate, average, max)
}

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)
private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH)

/**
 * One line describing a candidate: "Aujourd'hui 18:05–19:20 · 1 h 15 · FC moy. 132, max 171 bpm".
 *
 * The day is "Aujourd'hui", "Hier", or the date ("lun. 14 nov.") -- a background sync can
 * surface an activity hours after it ended, so the time alone would be ambiguous. The heart
 * rate part is left out when the window holds no reading at all, never shown as zero.
 */
fun candidateSummary(
    strings: CandidateStrings,
    start: Long,
    end: Long,
    heartRate: HeartRateSummary?,
    zone: ZoneId,
    nowSeconds: Long,
): String {
    val startAt = Instant.ofEpochSecond(start).atZone(zone)
    val endAt = Instant.ofEpochSecond(end).atZone(zone)
    val today = Instant.ofEpochSecond(nowSeconds).atZone(zone).toLocalDate()
    val day = when (startAt.toLocalDate()) {
        today -> strings.today()
        today.minusDays(1) -> strings.yesterday()
        else -> DAY.format(startAt)
    }

    val totalMinutes = ((end - start) / 60).coerceAtLeast(0)
    val duration = if (totalMinutes >= 60) strings.hoursMinutes(totalMinutes / 60, totalMinutes % 60) else strings.minutes(totalMinutes)

    val parts = mutableListOf("$day ${TIME.format(startAt)}–${TIME.format(endAt)}", duration)
    val average = heartRate?.averageHeartRate
    val max = heartRate?.maxHeartRate
    if (average != null && max != null) parts += strings.heartRate(average.roundToInt(), max)
    return parts.joinToString(" · ")
}

