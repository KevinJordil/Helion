package ch.kevinjordil.helion.strava

import ch.kevinjordil.helion.store.SportType

/**
 * The Strava `sport_type` value submitted with an upload (see [StravaApi.createUpload]).
 * This is independent of [tcxSport] -- it is read by Strava from the upload request itself,
 * not from the TCX body, so it can be exact even where TCX's own three-value schema cannot.
 *
 * Strava has a dedicated `Badminton` sport type, used directly. The others have no exact
 * Strava counterpart for what this strap can report (no GPS, no distance), so the closest
 * available type is used instead:
 * - [SportType.WALKING] -> `Walk`, an exact match.
 * - [SportType.RUNNING] -> `Run`, an exact match.
 * - [SportType.CYCLING] -> `Ride`, an exact match.
 * - [SportType.SWIMMING] -> `Swim`, an exact match (open water or pool, Strava does not
 *   distinguish at upload time).
 * - [SportType.OTHER] -> `Workout`, Strava's own generic catch-all, the closest fit for an
 *   activity this app cannot characterise any further.
 */
fun stravaSportType(sport: SportType): String = when (sport) {
    SportType.BADMINTON -> "Badminton"
    SportType.RUNNING -> "Run"
    SportType.CYCLING -> "Ride"
    SportType.WALKING -> "Walk"
    SportType.SWIMMING -> "Swim"
    SportType.OTHER -> "Workout"
}
