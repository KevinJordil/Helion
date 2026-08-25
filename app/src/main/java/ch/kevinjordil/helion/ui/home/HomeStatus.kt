package ch.kevinjordil.helion.ui.home

/**
 * The three non-nominal states Accueil must show instead of a dashboard, plus the nominal
 * one. Deliberately distinguishes [NoSource] from [EmptyArchive] the same way
 * [ch.kevinjordil.helion.ui.settings.SyncOutcome] distinguishes NotConfigured from
 * Unavailable: "nothing chosen yet" and "chosen but nothing read yet" call for different
 * copy and a different next action (Réglages vs. a sync).
 */
sealed interface HomeStatus {
    /** No export file has been chosen: point him at Réglages rather than a grid of dashes. */
    data object NoSource : HomeStatus

    /** A file is configured but the archive has nothing in it yet: invite a sync. */
    data object EmptyArchive : HomeStatus

    /** There is at least one sample somewhere in the store: show the dashboard. */
    data object Nominal : HomeStatus
}

/**
 * Resolves which of Accueil's states applies. Pure function of the two facts that decide
 * it, so it is testable without a database or Compose: [exportConfigured] mirrors
 * `ExportLocation.uri != null`, [hasAnyStoredSample] mirrors whether any metric's latest
 * reading came back non-null.
 */
fun resolveHomeStatus(exportConfigured: Boolean, hasAnyStoredSample: Boolean): HomeStatus = when {
    !exportConfigured -> HomeStatus.NoSource
    !hasAnyStoredSample -> HomeStatus.EmptyArchive
    else -> HomeStatus.Nominal
}
