package ch.kevinjordil.helion.activity

/**
 * Every number the three detection passes ([trimSlotOccurrence], [detectFreeSessions] and
 * [computeHeartRateBaseline]) depend on, bundled here rather than scattered through the
 * algorithms -- the same shape [ch.kevinjordil.helion.ui.sleep.SleepThresholds] and
 * [ch.kevinjordil.helion.ui.sleep.SleepPhaseThresholds] already use, so a future
 * owner-adjustable Réglages screen can hand in different numbers without any pass itself
 * changing.
 *
 * **Calibrated against the owner's first real training data**: a five-match badminton
 * tournament (Friday evening, roughly 18:15-00:30, heart rate climbing from an ~85 bpm
 * evening baseline to peaks of 144/160/155/160/177, falling back only to 93-105 between
 * matches -- never to rest) and a clean training block two days later (roughly
 * 19:10-21:00, sustained 140-176). Over that same export his own heart-rate percentiles
 * were p25=51, p50=58, p75=68, p90=93, p95=104, min=36, max=177. Measuring how long heart
 * rate stayed below a candidate floor during the tournament evening was decisive: below 90
 * bpm the longest dip was 17 minutes; below 100 bpm, 39 minutes; below 110 bpm, 63 minutes.
 * A single threshold cannot fit both numbers -- at 100+ it swallows ordinary evenings
 * between-match rest is only 10-20 bpm below a 100 floor -- so detection uses two
 * thresholds with hysteresis: a higher one to *enter* a session (confirms real effort
 * happened at all) and a lower one to *stay in* it (tolerant of the between-rally, between-
 * match lulls that a single flat threshold cannot survive). Both are expressed relative to
 * the owner's own resting rate and observed maximum, never as an absolute bpm: his resting
 * rate and ceiling will both drift over time, and a fixed number tuned on this one export
 * would be wrong for a future one, let alone for anyone else's wrist.
 */
data class DetectionThresholds(
    /**
     * How far back [computeHeartRateBaseline] looks to establish the owner's own resting
     * heart rate and ceiling. Thirty days is long enough to average out a handful of bad
     * nights or unusually stressful days, and matches the window
     * [ch.kevinjordil.helion.ui.quality.computeBaseline] already uses for the metric
     * screens' own personal-baseline comparison, so the owner is not shown two different
     * ideas of "normal" for the same kind of number.
     */
    val baselineWindowDays: Long = 30,

    /**
     * Distinct calendar days of heart-rate history required before a baseline is trusted
     * at all. The original guess here was 14 ("two weeks"), picked before any real export
     * existed to check it against. Against the owner's actual first export -- thirteen
     * calendar days -- the p25 resting estimate was already stable to within 1 bpm by day
     * 5 and exactly settled by day 9 (51 bpm from day 9 through the full 13-day window;
     * see the calibration notes in the repository history for the day-by-day figures).
     * Ten days keeps a deliberate margin below that observed stability point rather than
     * matching it exactly, while being short enough that a real export -- like this one --
     * can actually produce a trusted baseline instead of detecting nothing for its own
     * first two weeks. Until [minBaselineDays] distinct days exist,
     * [computeHeartRateBaseline] returns null and detection proposes nothing at all, which
     * is exactly the safe failure mode.
     */
    val minBaselineDays: Int = 10,

    /**
     * The low percentile of all-day heart-rate readings used as the resting-rate estimate.
     * 25% (not the bare minimum reading, one noisy low sample, and not a true resting
     * measurement -- this device gives none while awake) is what the calibration above was
     * measured against: p25 over the real 13-day export was 51 bpm, and every threshold
     * below is derived relative to that same figure.
     */
    val restingPercentile: Double = 0.25,

    /**
     * A floor under (observed max - resting), the "range" [HeartRateBaseline] scales
     * [enterFraction] and [floorFraction] against. Guards the degenerate case of someone
     * whose recorded days have not yet included any real effort -- max close to resting --
     * which would otherwise collapse both thresholds down near resting itself and make
     * ordinary daily noise register as "elevated effort". 40 bpm is comfortably below the
     * owner's own measured range (126 bpm: 177 max over a 51 bpm resting rate) so it never
     * engages against his real data; it only stops the multiplier from having nothing
     * meaningful to work with when there is not yet a real effort on record.
     */
    val minRangeBpm: Double = 40.0,

    /**
     * How far above resting, as a fraction of (observed max - resting), heart rate must
     * climb -- and hold for [minEntrySustainMinutes] -- to *enter* a session at all. 0.55
     * against the owner's own numbers (51 resting, 177 max, 126 bpm range) lands at
     * 51 + 0.55*126 ≈ 120 bpm: comfortably below every match peak (144-177) and every
     * training peak (140-176), and comfortably above the 93-105 bpm his heart rate actually
     * reached between matches -- so a between-match lull can never itself look like a fresh
     * session starting.
     */
    val enterFraction: Double = 0.55,

    /**
     * How far above resting, as a fraction of the same range, heart rate must fall below to
     * end a session (once [dipToleranceMinutes] of continuous time below it has passed) --
     * the lower half of the hysteresis pair with [enterFraction]. 0.32 against the same
     * numbers lands at 51 + 0.32*126 ≈ 90 bpm: this is the number the real tournament
     * evening was measured against directly -- the longest a between-match dip stayed below
     * 90 bpm was 17 minutes, while below 100 bpm the longest dip was 39 minutes. A floor at
     * 100 or higher shreds one tournament into five fragments; 90 holds it together as
     * intended while still sitting clearly above the 51-68 bpm (p25-p75) an ordinary quiet
     * evening occupies.
     */
    val floorFraction: Double = 0.32,

    /**
     * Consecutive minutes heart rate must hold at or above the *enter* threshold
     * ([HeartRateBaseline.enterThresholdBpm]) before a session is confirmed to have started
     * at all, rather than a single spike. Five minutes is short enough that the very first
     * rally of a tournament match still clears it (heart rate climbs fast under real
     * badminton effort -- the tournament's own five peaks each held well above 120 bpm for
     * much longer than five minutes at a stretch) while being long enough that a momentary
     * stress spike or a missed step off a curb cannot alone start a session.
     */
    val minEntrySustainMinutes: Int = 5,

    /**
     * The longest continuous stretch below the *floor* threshold
     * ([HeartRateBaseline.floorThresholdBpm]) -- or gap of missing data -- tolerated inside
     * an otherwise continuous session before it is considered over, rather than splitting
     * in two. This is the number measured directly against the real tournament evening: the
     * longest below-floor (90 bpm) run between any two of the five matches was 17 minutes.
     * 20 minutes clears that with a small margin -- enough to absorb the longest real gap
     * between matches (walking to the next court, a longer changeover) without also being
     * so long that it would stitch together two genuinely separate outings hours apart into
     * one nonsensical session.
     */
    val dipToleranceMinutes: Int = 20,

    /**
     * How far, in either direction, [trimSlotOccurrence] (pass 1) is allowed to follow a
     * session's real boundaries past the slot's *declared* start and end. A declared slot is
     * now an anchor for the session's identity, not a cage for its times -- arriving early to
     * warm up or playing on past the nominal end must not truncate the recorded session -- but
     * that following has to stop somewhere, or the same dip-tolerant merge that correctly holds
     * one session together across a between-rally lull would, given enough uninterrupted floor
     * time, eventually reach a wholly unrelated later elevation (climbing the stairs home,
     * showering) and stitch it onto the slot too -- exactly the "nine-hour block" failure mode
     * a motorcycle ride and an evening out once produced when nothing bounded a merge at all.
     *
     * Deliberately not a round number: it is derived from two thresholds already calibrated
     * elsewhere in this class rather than picked fresh. `2 * dipToleranceMinutes` (40 minutes)
     * gives a session two full dip-tolerance windows' worth of room to extend beyond the
     * declared edge before the margin itself becomes the limiting factor -- generous enough
     * that a single legitimate overrun (finishing a match, an extra warm-up set) is never the
     * thing that clips the boundary. The added `minEntrySustainMinutes` (5 minutes) is a small
     * safety buffer on top, so a session whose entry-confirming climb starts right at the
     * margin edge is not itself cut off by the margin before it has had the chance to confirm
     * itself. The sum, 45 minutes, is still far short of the hour-plus gap that would need to
     * separate two genuinely different outings for this margin to bridge them by accident.
     */
    val slotExtensionMarginMinutes: Int = 2 * dipToleranceMinutes + minEntrySustainMinutes,

    /**
     * A slot occurrence's trimmed effort span (its first to its last floor-crossing minute,
     * see [trimSlotOccurrence]) shorter than this is treated the same as a flat window: not
     * a real session, just a brief elevated blip inside a declared but unattended slot. Ten
     * minutes is short for genuine sport but long enough that a stray reading or two cannot
     * manufacture a session out of an evening he simply did not go.
     */
    val minSlotEffortMinutes: Int = 10,

    /**
     * The minimum total duration [detectFreeSessions] (pass 2) requires -- on top of the
     * [minEntrySustainMinutes] entry confirmation -- before a session becomes a candidate at
     * all. Pass 2 has no declared commitment backing it up the way pass 1 does, so it is the
     * pass most able to invent a session out of ordinary life, and a floor here keeps it
     * quiet unless the elevation is both real (confirmed by the entry gate) and sustained.
     * Twenty minutes was checked against the real export: every one of the thirty-two raw
     * floor-crossing stretches in the whole thirteen-day window that was neither a genuine
     * session nor cleared this floor was also rejected by the entry gate (and vice versa) --
     * the two checks do independent, complementary work, not the same job twice.
     */
    val minFreeSessionMinutes: Int = 20,
)
