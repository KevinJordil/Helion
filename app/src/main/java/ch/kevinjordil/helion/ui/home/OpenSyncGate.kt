package ch.kevinjordil.helion.ui.home

/**
 * Debounces Accueil's open-sync (see [performRefresh]'s caller in `HomeScreen`): a
 * background refresh should fire once per genuine app open, not once per recomposition and
 * not on every quick return from a two-second detour to Réglages and back -- both of which
 * remount Accueil in this app's hand-rolled navigation (see
 * [ch.kevinjordil.helion.ui.HelionNavHost]).
 *
 * One instance lives on [ch.kevinjordil.helion.AppContainer], which survives Accueil being
 * un/remounted by navigation and survives configuration changes, but not process death --
 * exactly matching what "on app open" should mean here: a fresh process always gets its
 * opening sync, a quick tab switch within the same running app does not get a second one.
 *
 * Debounce rule: sync again only once [minIntervalSeconds] has passed since the last
 * attempt recorded with [recordAttempt]. Before anything has ever been recorded,
 * [shouldSync] is always true, so the very first open always syncs.
 */
class OpenSyncGate(private val minIntervalSeconds: Long = DEFAULT_MIN_INTERVAL_SECONDS) {

    private var lastAttemptSeconds: Long? = null

    fun shouldSync(nowSeconds: Long): Boolean {
        val last = lastAttemptSeconds ?: return true
        return (nowSeconds - last) >= minIntervalSeconds
    }

    fun recordAttempt(nowSeconds: Long) {
        lastAttemptSeconds = nowSeconds
    }

    companion object {
        /**
         * Ten minutes: long enough that bouncing to Réglages and back, or a brief
         * app-switch, does not re-trigger a second sync moments after the first, short
         * enough that a genuinely new session (the next morning, right after training --
         * exactly when he opens the app, per the design brief) gets a fresh one.
         */
        const val DEFAULT_MIN_INTERVAL_SECONDS = 600L
    }
}
