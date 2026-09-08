package ch.kevinjordil.helion.ui.sleep

/**
 * How long after a night's own wake time it still counts as "news" for the morning
 * notification (see [sleepNightToNotify]), in seconds. Chosen generously enough to cover
 * the latest a night could plausibly end (a bedtime just before
 * [SleepThresholds.nightWindowEndHour], plus a long night) and still be picked up by an
 * ingest pass running anywhere in the same day -- 20 hours means a night that ended at,
 * say, 4am is still notifiable up to midnight the same day. Past that, the night is
 * treated as arriving late (a phone left off, a big backfill, a reinstall reading old
 * history) rather than as morning news, exactly the "not several days later" rule this
 * feature was asked for.
 */
const val SLEEP_NOTIFICATION_STALE_AFTER_SECONDS = 20 * 60 * 60L

/**
 * The one night, if any, the morning sleep-summary notification should cover on this
 * ingest pass -- or null when there is nothing to say.
 *
 * A night qualifies only when all of the following hold:
 * - [SleepEpisode.kind] is [SleepEpisodeKind.NIGHT] (defensive: [nights] is expected to be
 *   pre-filtered to nights already, the way [SleepReader.loadNights] returns them, but
 *   this does not assume it).
 * - Not [SleepEpisode.isInProgress]: the whole reason this is an ingest-triggered check,
 *   not a clock alarm, is that a night still being recorded (e.g. opened at 3am) must
 *   never be reported on as if it were over.
 * - Not [SleepEpisode.hasDataGap]: exactly the same reason the detail screen itself
 *   suppresses its own quality comparisons for a gappy night (see [SleepScreen]) --
 *   nothing honest can be said about "usual" or "in range" for a night Helion cannot
 *   fully account for, so nothing is said about it at all rather than something
 *   potentially misleading.
 * - Not already notified, per [isAlreadyNotified] (backed by
 *   [ch.kevinjordil.helion.store.NotifiedSleepNightDao] in the real implementation) --
 *   the "once per night, ever" rule, immune to a re-ingest, a re-analysis, or a reinstall
 *   reading the same archive.
 * - Not stale: [now] minus [SleepEpisode.wokeAt] must fall in `0..staleAfterSeconds`. The
 *   lower bound guards against a clock skew or a malformed export placing a "wake time" in
 *   the future; the upper bound is the "not several days later" rule from this feature's
 *   own spec -- see [SLEEP_NOTIFICATION_STALE_AFTER_SECONDS].
 *
 * When several nights qualify at once (only plausible if the app was never opened for a
 * stretch and several ingest passes' worth of nights all still fall inside the freshness
 * window), the single most recent one is returned: the notification is about last night,
 * not a backlog, and every older qualifying night is simply left for
 * [isAlreadyNotified] to keep excluding from now on -- notifying for it later, once it has
 * aged out of the freshness window, is exactly the staleness rule refusing it anyway.
 */
fun sleepNightToNotify(
    nights: List<SleepEpisode>,
    now: Long,
    isAlreadyNotified: (Long) -> Boolean,
    staleAfterSeconds: Long = SLEEP_NOTIFICATION_STALE_AFTER_SECONDS,
): SleepEpisode? = nights
    .asSequence()
    .filter { it.kind == SleepEpisodeKind.NIGHT }
    .filterNot { it.isInProgress }
    .filterNot { it.hasDataGap }
    .filter { (now - it.wokeAt) in 0..staleAfterSeconds }
    .filterNot { isAlreadyNotified(it.wokeAt) }
    .maxByOrNull { it.wokeAt }
