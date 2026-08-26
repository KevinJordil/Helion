package ch.kevinjordil.helion.customserver

import ch.kevinjordil.helion.store.SportType

/**
 * The `sport` field sent to the owner's own server: [SportType]'s own enum constant name,
 * lower-cased. Stable and locale-independent on purpose -- unlike Strava's own vocabulary
 * or the French slugs the Downloads file name is built from, this is a contract with code
 * the owner writes himself, so it must never change just because the app's own French
 * labels do.
 */
fun customServerSportSlug(sport: SportType): String = sport.name.lowercase()
