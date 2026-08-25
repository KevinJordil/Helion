package ch.kevinjordil.helion.strava

import ch.kevinjordil.helion.store.MinuteSample
import ch.kevinjordil.helion.store.SportType
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The TCX `<Activity Sport="...">` this [SportType] renders as. TCX v2's schema only
 * defines three sport values -- `Running`, `Biking`, `Other` -- so most of Helion's sport
 * types fall back to `Other`; that is a limitation of the file format, not a loss of
 * information, since the real sport travels separately as the upload's `sport_type` form
 * field (see [stravaSportType], used by [ch.kevinjordil.helion.strava.StravaApi] and read
 * by Strava regardless of what the TCX body itself says).
 */
fun tcxSport(sport: SportType): String = when (sport) {
    SportType.RUNNING -> "Running"
    SportType.CYCLING -> "Biking"
    SportType.BADMINTON, SportType.WALKING, SportType.SWIMMING, SportType.OTHER -> "Other"
}

private val ISO_INSTANT: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

private fun isoTime(epochSeconds: Long): String = ISO_INSTANT.format(Instant.ofEpochSecond(epochSeconds))

private fun escapeXml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

/**
 * Renders one activity as a TCX v2 document: start time, duration and a heart-rate-only
 * trackpoint series, nothing else. [samples] should already be filtered to
 * `[startTimestamp, endTimestamp]` and ordered by timestamp -- see
 * [ch.kevinjordil.helion.store.MinuteSampleDao.between], the only source this ever reads
 * from. Minutes with no heart-rate reading are skipped rather than filled with an invented
 * value: a `<Trackpoint>` with no `<HeartRateBpm>` is valid TCX, but omitting the point
 * entirely is simpler and just as honest for a minute-resolution strap recording.
 *
 * `DistanceMeters`, `Calories`, `Intensity` and `TriggerMethod` inside `<Lap>` are mandatory
 * elements of the TCX v2 schema with no "not measured" representation; the zero/placeholder
 * values used here are the file format's own convention for "not tracked", the same one
 * indoor-trainer and strap-only recordings use everywhere else, not an invented reading.
 * No `<Position>` (there is no GPS on this strap) and no `<Cadence>` are written.
 */
fun writeTcx(
    sport: SportType,
    startTimestamp: Long,
    endTimestamp: Long,
    samples: List<MinuteSample>,
): String {
    val startIso = isoTime(startTimestamp)
    val durationSeconds = (endTimestamp - startTimestamp).coerceAtLeast(0)

    val trackpoints = samples
        .filter { it.timestamp in startTimestamp..endTimestamp }
        .sortedBy { it.timestamp }
        .joinToString(separator = "\n") { sample ->
            val time = isoTime(sample.timestamp)
            if (sample.heartRate != null) {
                "        <Trackpoint>\n" +
                    "          <Time>$time</Time>\n" +
                    "          <HeartRateBpm><Value>${sample.heartRate}</Value></HeartRateBpm>\n" +
                    "        </Trackpoint>"
            } else {
                "        <Trackpoint>\n" +
                    "          <Time>$time</Time>\n" +
                    "        </Trackpoint>"
            }
        }

    return """<?xml version="1.0" encoding="UTF-8"?>
<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2 https://www8.garmin.com/xmlschemas/TrainingCenterDatabasev2.xsd">
  <Activities>
    <Activity Sport="${tcxSport(sport)}">
      <Id>${escapeXml(startIso)}</Id>
      <Lap StartTime="${escapeXml(startIso)}">
        <TotalTimeSeconds>$durationSeconds</TotalTimeSeconds>
        <DistanceMeters>0</DistanceMeters>
        <Calories>0</Calories>
        <Intensity>Active</Intensity>
        <TriggerMethod>Manual</TriggerMethod>
        <Track>
$trackpoints
        </Track>
      </Lap>
    </Activity>
  </Activities>
</TrainingCenterDatabase>
"""
}
