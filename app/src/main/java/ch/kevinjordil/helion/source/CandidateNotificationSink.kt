package ch.kevinjordil.helion.source

import ch.kevinjordil.helion.store.Activity

/**
 * Wired onto [Ingestor.notifier] the same way [ch.kevinjordil.helion.activity.ActivityDetector]
 * is wired onto [Ingestor.detector]: set after construction by
 * [ch.kevinjordil.helion.AppContainer], left null in tests that have no interest in
 * notifications at all -- a null [Ingestor.notifier] simply means nothing is ever notified,
 * the safe default.
 *
 * The real implementation is
 * [ch.kevinjordil.helion.notification.CandidateNotifier]; this interface only exists so
 * `source` -- which owns no Android notification machinery -- does not need to depend on
 * it, and so a test can substitute a plain fake instead of standing up a real
 * `NotificationManager`.
 *
 * [notifyNewCandidates] returns whether a notification was actually posted. [Ingestor] only
 * marks a candidate as [Activity.notified] when this returns `true`: a candidate that could
 * not be shown -- notifications turned off in Réglages, or Android's runtime permission not
 * granted -- keeps its one-shot chance, to be included in the next pass' batch once the
 * setting is turned on or the permission is granted. `candidates` is always non-empty; the
 * batching decision (one candidate vs. several) is this implementation's own job, not
 * [Ingestor]'s.
 */
fun interface CandidateNotificationSink {
    suspend fun notifyNewCandidates(candidates: List<Activity>): Boolean
}
