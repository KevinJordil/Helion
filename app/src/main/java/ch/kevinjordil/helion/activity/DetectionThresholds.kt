package ch.kevinjordil.helion.activity

/**
 * Every number the three detection passes ([trimSlotOccurrence], [detectFreeSessions] and
 * [computeHeartRateBaseline]) depend on, bundled here rather than scattered through the
 * algorithms -- the same shape [ch.kevinjordil.helion.ui.sleep.SleepThresholds] and
 * [ch.kevinjordil.helion.ui.sleep.SleepPhaseThresholds] already use, so a future
 * owner-adjustable Réglages screen can hand in different numbers without any pass itself
 * changing.
 *
 * **None of these defaults have been checked against a real training session.** The owner
 * has not played in weeks and resumes after this step ships, so there is no genuine
 * badminton (or running, or cycling) heart-rate trace anywhere in the archive to tune
 * against. Every number below is a documented, conservative guess, biased the same
 * direction the whole module is biased: when in doubt, detect nothing. A missed session
 * costs thirty seconds on the day timeline; an invented one is the exact problem this
 * project exists to end.
 */
data class DetectionThresholds(
    /**
     * How far back [computeHeartRateBaseline] looks to establish the owner's own resting
     * heart rate and spread. Thirty days is long enough to average out a handful of bad
     * nights or unusually stressful days, and matches the window
     * [ch.kevinjordil.helion.ui.quality.computeBaseline] already uses for the metric
     * screens' own personal-baseline comparison, so the owner is not shown two different
     * ideas of "normal" for the same kind of number.
     */
    val baselineWindowDays: Long = 30,

    /**
     * Distinct calendar days of heart-rate history required before a baseline is trusted
     * at all. Deliberately higher than [ch.kevinjordil.helion.ui.quality.MIN_BASELINE_DAYS]
     * (5): a wrong personal-baseline caption on a metric screen is a mildly confusing
     * label, but a wrong elevation threshold here silently decides whether real minutes of
     * his life get proposed as activities or not. Two weeks is asked for instead, and
     * until then [computeHeartRateBaseline] returns null and detection proposes nothing at
     * all, which is exactly the safe failure mode.
     */
    val minBaselineDays: Int = 14,

    /**
     * The low percentile of all-day heart-rate readings used as the resting-rate estimate
     * itself, rather than the bare minimum reading (one noisy low sample) or a true resting
     * measurement (this device gives none while awake). 15% is low enough to land in the
     * genuinely quiet stretches of an ordinary day -- sitting, sleeping -- without being so
     * extreme a percentile that a single unusually still hour dominates it.
     */
    val restingPercentile: Double = 0.15,

    /** Lower edge of the percentile band [computeHeartRateBaseline] uses to measure the owner's own day-to-day spread. */
    val spreadLowPercentile: Double = 0.15,

    /** Upper edge of that same band -- see [spreadLowPercentile]. Deliberately not the 99th or the max: one hard effort or one anxious spike must not blow the whole spread out and make every other day look artificially quiet by comparison. */
    val spreadHighPercentile: Double = 0.85,

    /**
     * A floor under the measured spread itself. Someone whose recorded days are all very
     * similar (little data, or a genuinely flat lifestyle so far) would otherwise get a
     * near-zero spread, which -- multiplied by [elevationSpreadMultiplier] -- would turn
     * "elevated" into a razor's edge that ordinary daily noise crosses constantly. Five bpm
     * is a conservative floor: it does not manufacture sensitivity where the data has not
     * earned it, but stops the multiplier from collapsing to nothing.
     */
    val minSpreadBpm: Double = 5.0,

    /**
     * How many multiples of the owner's own spread above his resting rate counts as
     * "elevated effort", before [minElevationBpm] is applied as a floor on top. 2.5 is a
     * deliberately wide margin -- ordinary daily variation (a flight of stairs, an
     * argument, a hot afternoon) should not itself clear it; only something that actually
     * looks like exercise should.
     */
    val elevationSpreadMultiplier: Double = 2.5,

    /**
     * An absolute floor on top of [elevationSpreadMultiplier]: even a very spread-out
     * heart-rate history must not turn "elevated" into something within, say, 10 bpm of
     * resting. 25 bpm above resting is a substantial, unmistakable rise for anyone's
     * physiology, and stays in force regardless of how the multiplier alone would resolve
     * on a particular data set.
     */
    val minElevationBpm: Double = 25.0,

    /**
     * A slot occurrence's trimmed effort span (its first to its last elevated minute, see
     * [trimSlotOccurrence]) shorter than this is treated the same as a flat window: not a
     * real session, just a brief elevated blip inside a declared but unattended slot. Ten
     * minutes is short for genuine sport but long enough that a stray reading or two cannot
     * manufacture a session out of an evening he simply did not go.
     */
    val minSlotEffortMinutes: Int = 10,

    /**
     * The minimum sustained duration [detectFreeSessions] (pass 2) requires before an
     * elevated stretch becomes a candidate at all, on top of [minElevationBpm]. Twenty
     * minutes is conservative on purpose: pass 2 has no declared commitment backing it up
     * the way pass 1 does, so it is the pass most able to invent a session out of ordinary
     * life (a brisk errand, a stressful call), and a longer floor keeps it quiet unless the
     * elevation is both real and sustained.
     */
    val minFreeSessionMinutes: Int = 20,

    /**
     * The longest dip below the elevation threshold -- or gap of missing data -- that
     * [detectFreeSessions] and [trimSlotOccurrence] tolerate inside an otherwise continuous
     * session, rather than splitting it in two. This is specifically calibrated against
     * badminton: rallies are short bursts separated by picking up shuttlecocks, switching
     * ends or a brief water break, and a naive rule with no tolerance would shred one real
     * match into a dozen disconnected fragments, none of which would even clear
     * [minFreeSessionMinutes] on its own. Four minutes absorbs an ordinary between-rally
     * lull without also being long enough to stitch together two genuinely separate
     * outings (an errand, then a much later walk) into one nonsensical session.
     */
    val maxDipMinutes: Int = 4,
)
