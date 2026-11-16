package ch.kevinjordil.helion.ui.home

/**
 * Debounces the opening sync ([ForegroundSync.onAppOpened], called from
 * [ch.kevinjordil.helion.MainActivity.onResume]): a sync should fire once per genuine app
 * open, not on every quick return from another app or from a system dialog -- each of which
 * resumes the activity again.
 *
 * One instance lives on [ch.kevinjordil.helion.AppContainer]: it survives configuration
 * changes, but not process death -- exactly matching what "on app open" should mean here: a
 * fresh process always gets its opening sync, a quick back-and-forth within the same running
 * app does not get a second one. Every foreground pass records itself here (see
 * [ForegroundSync.syncNow]), so a pull-to-refresh also counts as the latest attempt.
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
         * Ten minutes: long enough that a brief app-switch does not re-trigger a second
         * sync moments after the first, short enough that a genuinely new session (the next morning, right after training --
         * exactly when he opens the app, per the design brief) gets a fresh one.
         */
        const val DEFAULT_MIN_INTERVAL_SECONDS = 600L
    }
}
