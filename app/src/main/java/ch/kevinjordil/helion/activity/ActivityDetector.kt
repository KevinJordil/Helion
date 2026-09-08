package ch.kevinjordil.helion.activity

import ch.kevinjordil.helion.store.Activity
import ch.kevinjordil.helion.store.ActivityOrigin
import ch.kevinjordil.helion.store.ActivityStatus
import ch.kevinjordil.helion.store.HelionDatabase
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
 * **Pass 3, "manual always wins", still holds for everything settled**: a
 * [ActivityStatus.CONFIRMED], [ActivityStatus.PUBLISHED] or [ActivityStatus.DISMISSED] row,
 * and any [ActivityOrigin.MANUAL] one regardless of status, is never updated or deleted by
 * this class under any circumstance -- there is simply no code path here that touches one
 * of those. The one deliberate exception is an untouched [ActivityStatus.CANDIDATE] whose
 * [Activity.provisional] flag is still true (see that field's own kdoc for why): detection
 * can run mid-session, and the candidate it produces then is only as long as the data that
 * existed at that moment, not the real session. Such a row *is* re-evaluated on every later
 * [detect] call whose window reaches it, and updated in place -- same id, same `notified`,
 * grown to whatever the current data now supports -- rather than being either frozen
 * forever or duplicated next to a second, overlapping candidate. The moment the owner does
 * anything to that row (confirms, dismisses, or hand-edits it), [Activity.provisional]
 * is cleared and it becomes exactly as untouchable as every other settled row.
 *
 * [noteFor] renders the localised evidence line stored on [Activity.detectionContext] --
 * `activity_candidate_note` in `strings.xml` -- kept as an injected function rather than a
 * direct string-resource lookup so the passes and this orchestrator stay testable without
 * an Android `Context`. It is deliberately never written to [Activity.notes]: that field is
 * for the owner's own words, and only those ever leave the device on export -- see
 * [Activity.detectionContext]'s own kdoc.
 */
class ActivityDetector(
    private val db: HelionDatabase,
    private val zone: ZoneId,
    private val now: () -> Long,
    private val thresholds: DetectionThresholds = DetectionThresholds(),
    private val noteFor: (minHeartRate: Int, maxHeartRate: Int, restingBpm: Int) -> String,
) {
    /** Returns how many *new* candidates were created; growing an existing provisional one does not count -- purely for logging/telemetry, callers do not otherwise need it. */
    suspend fun detect(from: Long, to: Long): Int {
        val anchor = now()
        val baselineFrom = anchor - thresholds.baselineWindowDays * SECONDS_PER_DAY
        val baseline = computeHeartRateBaseline(db.minuteSamples().between(baselineFrom, anchor), zone, thresholds)
            ?: return 0 // Not enough personal history yet: detect nothing, on principle -- see DetectionThresholds.minBaselineDays.

        // The true edge of what this archive currently holds, independent of this call's
        // own [from, to) window (which, for [ArchiveReanalyzer]'s historical slices, sits
        // well inside already-known data). A boundary that lands exactly on this edge means
        // "no data existed yet to say what happens next", not a real, observed stop -- see
        // [Activity.provisional]'s own kdoc.
        val dataStart = db.minuteSamples().earliestTimestamp()
        val dataEnd = db.minuteSamples().latestTimestamp()?.plus(60)
        fun isProvisional(start: Long, end: Long) = (dataStart != null && start <= dataStart) || (dataEnd != null && end >= dataEnd)

        var created = 0
        val newlyCreatedRanges = mutableListOf<LongRange>()

        // Pass 1: slots.
        for (slot in db.slots().active()) {
            for (occurrence in occurrencesBetween(slot, from, to, zone)) {
                val overlapping = db.activities().overlapping(occurrence.start, occurrence.end)
                // A still-provisional slot candidate of this same slot is the one kind of
                // overlap that does not mean "already decided" -- see this class' own kdoc.
                val growable = overlapping.singleOrNull {
                    it.status == ActivityStatus.CANDIDATE && it.origin == ActivityOrigin.SLOT && it.slotId == slot.id && it.provisional
                }
                // A cheap, deliberately coarse early-out: any *other* existing activity
                // anywhere in the *declared* range means this occurrence has already been
                // looked at (confirmed, dismissed, or already proposed and settled), so
                // trimming it again would risk a second candidate next to -- or nagging
                // about -- one that was already handled. See the module's "when in doubt,
                // propose nothing" rule.
                if (overlapping.isNotEmpty() && growable == null) continue

                // Read wider than the declared range -- trimSlotOccurrence may follow the
                // real session up to slotExtensionMarginMinutes past either edge, and needs
                // the minutes there to do so.
                val marginSeconds = thresholds.slotExtensionMarginMinutes * 60L
                val minutes = db.minuteSamples().between(occurrence.start - marginSeconds, occurrence.end + marginSeconds)
                val trimmed = trimSlotOccurrence(occurrence, minutes, baseline, thresholds) ?: continue

                if (growable != null) {
                    val stillProvisional = isProvisional(trimmed.start, trimmed.end)
                    if (trimmed.start != growable.startTimestamp || trimmed.end != growable.endTimestamp || stillProvisional != growable.provisional) {
                        // Never grow into a range some *other* row already claims -- exactly
                        // the same guard a brand-new candidate is held to below.
                        val blockers = db.activities().overlapping(trimmed.start, trimmed.end).filter { it.id != growable.id }
                        if (blockers.isEmpty()) {
                            db.activities().update(
                                growable.copy(
                                    startTimestamp = trimmed.start,
                                    endTimestamp = trimmed.end,
                                    detectionContext = noteFor(trimmed.minHeartRate, trimmed.maxHeartRate, baseline.restingBpm.roundToInt()),
                                    provisional = stillProvisional,
                                ),
                            )
                        }
                    }
                    newlyCreatedRanges.add(trimmed.start until trimmed.end)
                    continue
                }

                if (db.activities().overlapping(trimmed.start, trimmed.end).isNotEmpty()) continue
                db.activities().upsert(
                    Activity(
                        startTimestamp = trimmed.start,
                        endTimestamp = trimmed.end,
                        sport = slot.sport,
                        title = slot.label,
                        notes = null,
                        detectionContext = noteFor(trimmed.minHeartRate, trimmed.maxHeartRate, baseline.restingBpm.roundToInt()),
                        origin = ActivityOrigin.SLOT,
                        status = ActivityStatus.CANDIDATE,
                        slotId = slot.id,
                        provisional = isProvisional(trimmed.start, trimmed.end),
                    ),
                )
                created++
                newlyCreatedRanges.add(trimmed.start until trimmed.end)
            }
        }

        // Pass 2: free detection, only over time pass 1 and the existing, *settled* archive
        // have not already claimed. A still-provisional DETECTED candidate is deliberately
        // left out of that exclusion, the same way pass 1's own provisional slot candidates
        // are handled above, so the merge below can re-absorb it and keep growing it rather
        // than being permanently blocked by its own earlier, incomplete self.
        // `overlapping(from, to)` also picks up any activity that merely touches this
        // window's edges from outside it, which is exactly what must not be re-detected
        // (or re-grown into) either.
        val existingInWindow = db.activities().overlapping(from, to)
        val growableDetected = existingInWindow.filter {
            it.status == ActivityStatus.CANDIDATE && it.origin == ActivityOrigin.DETECTED && it.provisional
        }
        val growableDetectedIds = growableDetected.map { it.id }.toSet()
        val settledRanges = existingInWindow.filterNot { it.id in growableDetectedIds }.map { it.startTimestamp until it.endTimestamp }
        val excludedRanges = settledRanges + newlyCreatedRanges
        val sessions = detectFreeSessions(db.minuteSamples().between(from, to), excludedRanges, baseline, thresholds)

        val consumedGrowableIds = mutableSetOf<Long>()
        for (session in sessions) {
            // A single provisional candidate can only ever grow into a single merged
            // session: this is what keeps the owner opening the app several times mid-way
            // through the same training converging on one row instead of fragmenting into
            // several, since every pass re-derives the same one merged block from scratch.
            val growable = growableDetected.firstOrNull {
                it.id !in consumedGrowableIds && it.startTimestamp < session.end && it.endTimestamp > session.start
            }
            if (growable != null) {
                consumedGrowableIds += growable.id
                val stillProvisional = isProvisional(session.start, session.end)
                if (session.start != growable.startTimestamp || session.end != growable.endTimestamp || stillProvisional != growable.provisional) {
                    val blockers = db.activities().overlapping(session.start, session.end).filter { it.id != growable.id }
                    if (blockers.isEmpty()) {
                        db.activities().update(
                            growable.copy(
                                startTimestamp = session.start,
                                endTimestamp = session.end,
                                detectionContext = noteFor(session.minHeartRate, session.maxHeartRate, baseline.restingBpm.roundToInt()),
                                provisional = stillProvisional,
                            ),
                        )
                    }
                }
                continue
            }

            if (db.activities().overlapping(session.start, session.end).isNotEmpty()) continue
            db.activities().upsert(
                Activity(
                    startTimestamp = session.start,
                    endTimestamp = session.end,
                    // Heart rate alone never identifies which sport was played -- a
                    // motorcycle ride and a river descent read exactly the same as a
                    // badminton match here. A slot-origin candidate gets its sport from the
                    // slot the owner himself named and configured (see the pass above); a
                    // freely detected one has no such signal at all, so it gets none rather
                    // than a guess. See Activity.sport's own kdoc.
                    sport = null,
                    title = null,
                    notes = null,
                    detectionContext = noteFor(session.minHeartRate, session.maxHeartRate, baseline.restingBpm.roundToInt()),
                    origin = ActivityOrigin.DETECTED,
                    status = ActivityStatus.CANDIDATE,
                    slotId = null,
                    provisional = isProvisional(session.start, session.end),
                ),
            )
            created++
        }

        return created
    }
}
