package ch.kevinjordil.helion.source

/**
 * Wired onto [Ingestor.sleepNotifier] the same way [ch.kevinjordil.helion.activity.ActivityDetector]
 * is wired onto [Ingestor.detector] and [CandidateNotificationSink] onto [Ingestor.notifier]:
 * set after construction by [ch.kevinjordil.helion.AppContainer], left null in tests that
 * have no interest in the sleep-summary notification at all -- a null [Ingestor.sleepNotifier]
 * simply means the check never runs, the safe default.
 *
 * Unlike [CandidateNotificationSink], this interface carries no candidate list and no
 * return value for [Ingestor] to act on: everything the decision needs -- which night, if
 * any, is due; whether it was already notified; whether the owner's own setting and
 * Android's runtime permission actually allow posting -- lives behind this one call,
 * inside the real implementation
 * ([ch.kevinjordil.helion.notification.SleepNightNotifier]). [Ingestor] only decides *when*
 * to ask, never *what* the answer is: see [Ingestor]'s own kdoc for why this hook only
 * fires on a pass that actually stored new minute samples or new stage segments -- nothing
 * new can complete a night that was not already complete on the previous pass, so a pass
 * with nothing new has nothing to check.
 */
fun interface SleepNightNotificationSink {
    suspend fun checkForCompletedNight()
}
