package ch.kevinjordil.helion.activity

import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.store.ActivityOrigin
import ch.kevinjordil.helion.store.ActivityStatus
import ch.kevinjordil.helion.store.HelionDatabase
import ch.kevinjordil.helion.store.SportType
import java.time.ZoneId
import kotlin.math.roundToInt

private const val SECONDS_PER_DAY = 86_400L

/**
 * Runs all three detection passes over `[from, to)` (Unix seconds) and inserts whatever
 * candidates survive as [ActivityStatus.CANDIDATE] rows. This is the only place the three
 * passes are wired together; each pass itself ([trimSlotOccurrence], [detectFreeSessions],
 * [computeHeartRateBaseline]) is a plain function over data already in hand, so this class'
 * own job is: read the archive, hand each pass what it needs, and -- the part every pass
 * depends on for correctness -- never insert over a range [ActivityDao.overlapping] already
 * reports as decided.
 *
 * **Pass 3, "manual always wins", is not a pass at all here**: this class never updates or
 * deletes an existing [Activity] row, of any origin or status, under any circumstance. It
 * only ever inserts a new row, and only after confirming (via
 * [ch.kevinjordil.helion.store.ActivityDao.overlapping]) that the exact range it is about
 * to insert has no existing row at all. A manual activity, once created, therefore can
 * never be recalculated, overwritten or merged away by a later [detect] call -- there is
 * simply no code path here that touches a row it did not just create.
 *
 * [noteFor] renders the localised evidence line stored on [Activity.notes] --
 * `activity_candidate_note` in `strings.xml` -- kept as an injected function rather than a
 * direct string-resource lookup so the passes and this orchestrator stay testable without
 * an Android `Context`.
 */
class ActivityDetector(
    private val db: HelionDatabase,
    private val zone: ZoneId,
    private val now: () -> Long,
    private val thresholds: DetectionThresholds = DetectionThresholds(),
    private val noteFor: (minHeartRate: Int, maxHeartRate: Int, restingBpm: Int) -> String,
) {
    /** Returns how many candidates were created, purely for logging/telemetry; callers do not otherwise need it. */
    suspend fun detect(from: Long, to: Long): Int {
        val anchor = now()
        val baselineFrom = anchor - thresholds.baselineWindowDays * SECONDS_PER_DAY
        val baseline = computeHeartRateBaseline(db.minuteSamples().between(baselineFrom, anchor), zone, thresholds)
            ?: return 0 // Not enough personal history yet: detect nothing, on principle -- see DetectionThresholds.minBaselineDays.

        var created = 0
        val newlyCreatedRanges = mutableListOf<LongRange>()

        // Pass 1: slots.
        for (slot in db.slots().active()) {
            for (occurrence in occurrencesBetween(slot, from, to, zone)) {
                // A cheap, deliberately coarse early-out: any existing activity anywhere in
                // the *declared* range means this occurrence has already been looked at
                // (confirmed, dismissed, or already proposed), so trimming it again would
                // risk a second candidate next to -- or nagging about -- one that was
                // already handled. See the module's "when in doubt, propose nothing" rule.
                if (db.activities().overlapping(occurrence.start, occurrence.end).isNotEmpty()) continue

                val minutes = db.minuteSamples().between(occurrence.start, occurrence.end)
                val trimmed = trimSlotOccurrence(occurrence, minutes, baseline, thresholds) ?: continue

                if (db.activities().overlapping(trimmed.start, trimmed.end).isNotEmpty()) continue
                db.activities().upsert(
                    Activity(
                        startTimestamp = trimmed.start,
                        endTimestamp = trimmed.end,
                        sport = slot.sport,
                        title = slot.label,
                        notes = noteFor(trimmed.minHeartRate, trimmed.maxHeartRate, baseline.restingBpm.roundToInt()),
                        origin = ActivityOrigin.SLOT,
                        status = ActivityStatus.CANDIDATE,
                        slotId = slot.id,
                    ),
                )
                created++
                newlyCreatedRanges.add(trimmed.start until trimmed.end)
            }
        }

        // Pass 2: free detection, only over time pass 1 and the existing archive have not
        // already claimed. `overlapping(from, to)` also picks up any activity that merely
        // touches this window's edges from outside it, which is exactly what must not be
        // re-detected either.
        val existingRanges = db.activities().overlapping(from, to).map { it.startTimestamp until it.endTimestamp }
        val excludedRanges = existingRanges + newlyCreatedRanges
        val sessions = detectFreeSessions(db.minuteSamples().between(from, to), excludedRanges, baseline, thresholds)

        for (session in sessions) {
            if (db.activities().overlapping(session.start, session.end).isNotEmpty()) continue
            db.activities().upsert(
                Activity(
                    startTimestamp = session.start,
                    endTimestamp = session.end,
                    // The sport is genuinely unknown -- heart rate alone never identifies
                    // which sport was played -- so this is left for the owner to set on
                    // review, exactly like a manually drawn activity starts out.
                    sport = SportType.OTHER,
                    title = null,
                    notes = noteFor(session.minHeartRate, session.maxHeartRate, baseline.restingBpm.roundToInt()),
                    origin = ActivityOrigin.DETECTED,
                    status = ActivityStatus.CANDIDATE,
                    slotId = null,
                ),
            )
            created++
        }

        return created
    }
}
