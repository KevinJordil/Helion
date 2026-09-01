package ch.kevinjordil.helion.customserver

import ch.kevinjordil.helion.store.SportType
import ch.kevinjordil.helion.store.sportSlug

/**
 * The `sport` field sent to the owner's own server: [sportSlug], the same stable
 * hyphenated identifier the Downloads file name is built from. Stable and
 * locale-independent on purpose -- this is a contract with code the owner writes himself,
 * so it must never change just because the app's own French labels do.
 */
fun customServerSportSlug(sport: SportType): String = sportSlug(sport)
