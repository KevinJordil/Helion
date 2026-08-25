package ch.kevinjordil.helion.ui.quality

/**
 * Where a value sits relative to some comparison point -- either [ch.kevinjordil.helion.ui.quality.PersonalBaseline]'s
 * personal baseline or [ch.kevinjordil.helion.ui.quality.ReferenceIndicator]'s external
 * reference. Purely descriptive, never a verdict: direction is not quality (lower stress is
 * better, higher HRV generally is, more steps depends on the goal), so nothing that renders
 * a [Position] may say "good" or "bad" -- only where the value sits.
 */
enum class Position { BELOW, USUAL, ABOVE }
