package ch.kevinjordil.helion.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.BuildConfig
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType

/**
 * Réglages' eight top-level entries -- what used to be one long, unevenly-presented scroll
 * (Gadgetbridge export location, profile, steps goal, the Strava server, Health Connect,
 * notifications, the archive re-analysis, and the build stamp) is now a short list, each
 * entry opening its own focused sub-screen. [id] is the navigation route
 * [ch.kevinjordil.helion.ui.HelionNavHost] appends after `"settings:"`; [titleRes] is used
 * both as this entry's own label in [SettingsScreen] and, unchanged, as the sub-screen's own
 * header in [SettingsSectionScreen] -- one string, one place it is decided, so the two can
 * never drift into the "Envoi vers Strava" vs. "Serveur personnel" kind of mismatch this
 * reorganisation exists to remove.
 *
 * Named for what the owner wants to do (see each [titleRes]), not for the subsystem behind
 * it -- [HEALTH_CONNECT] is the one deliberate exception, since "Health Connect" already is
 * the name the owner knows it by, on this phone and in Android's own settings.
 */
enum class SettingsSection(val id: String, val titleRes: Int) {
    SOURCE("source", R.string.settings_entry_source_title),
    PROFILE("profile", R.string.profile_section_title),
    GOALS("goals", R.string.settings_entry_goals_title),
    STRAVA("strava", R.string.custom_server_section_title),
    HEALTH_CONNECT("health_connect", R.string.health_connect_section_title),
    NOTIFICATIONS("notifications", R.string.notification_settings_section_title),
    MAINTENANCE("maintenance", R.string.settings_entry_maintenance_title),
    ABOUT("about", R.string.settings_entry_about_title),
}

/**
 * Réglages' own top level: a short, uniformly-presented list of [SettingsSection] entries,
 * each showing its label and a one-line status/value preview (see `settingsEntryPreview`),
 * nothing else. Every entry is rendered by the exact same [SettingsEntryRow], which is the
 * whole point -- the unevenness the owner reported was as much about mixed presentation as
 * about ordering, and one shared row composable makes a second, differently-styled entry
 * impossible to add by accident.
 */
@Composable
fun SettingsScreen(container: AppContainer, onOpenSection: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tab_settings).uppercase(), style = HelionType.label, color = colors.textSecondary)

        SettingsSection.entries.forEach { section ->
            SettingsEntryRow(
                label = stringResource(section.titleRes),
                value = settingsEntryPreview(section, container),
                onClick = { onOpenSection(section.id) },
            )
            HorizontalDivider(color = colors.divider)
        }
    }
}

/**
 * One row of [SettingsScreen]'s list: the entry's own label, a short status/value preview
 * underneath, and a trailing ">" that marks it as opening something rather than acting
 * directly -- the same distinction a button (acts now) and this row (navigates) need to keep
 * clear at a glance. Every entry uses this composable and nothing else, so label, value and
 * spacing can never diverge entry to entry.
 */
@Composable
private fun SettingsEntryRow(label: String, value: String, onClick: () -> Unit) {
    val colors = HelionThemeTokens.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label.uppercase(), style = HelionType.label, color = colors.textPrimary)
            Text(value, style = HelionType.bodySmall, color = colors.textSecondary)
        }
        Text(">", style = HelionType.label, color = colors.textTertiary)
    }
}

/**
 * The one-line status [SettingsEntryRow] shows under each entry's label -- a plain read of
 * the same state its sub-screen edits, never a value tracked separately, so it can never go
 * stale relative to what the sub-screen actually shows.
 */
@Composable
private fun settingsEntryPreview(section: SettingsSection, container: AppContainer): String = when (section) {
    SettingsSection.SOURCE -> if (container.exportLocation.uri != null) {
        stringResource(R.string.settings_entry_source_configured)
    } else {
        stringResource(R.string.export_location_none)
    }
    SettingsSection.PROFILE -> if (container.profile.isComplete) {
        stringResource(R.string.settings_entry_profile_complete)
    } else {
        stringResource(R.string.settings_entry_profile_incomplete)
    }
    SettingsSection.GOALS -> stringResource(R.string.settings_entry_goals_value, container.stepsGoal.value)
    SettingsSection.STRAVA -> if (container.customServerConfig.isConfigured) {
        stringResource(R.string.settings_status_configured)
    } else {
        stringResource(R.string.settings_status_not_configured)
    }
    SettingsSection.HEALTH_CONNECT -> if (container.healthConnectConfig.enabled) {
        stringResource(R.string.settings_status_enabled)
    } else {
        stringResource(R.string.settings_status_disabled)
    }
    SettingsSection.NOTIFICATIONS -> if (container.notificationPreference.enabled) {
        stringResource(R.string.settings_status_enabled)
    } else {
        stringResource(R.string.settings_status_disabled)
    }
    SettingsSection.MAINTENANCE -> stringResource(R.string.settings_entry_maintenance_value)
    SettingsSection.ABOUT -> BuildConfig.BUILD_STAMP
}

/**
 * One [SettingsSection]'s own screen: the shared back link, the shared uppercase header
 * (the section's own [SettingsSection.titleRes], identical to what [SettingsScreen] just
 * showed as this entry's label), then the section's own fields -- laid out as direct
 * siblings of this `Column`, not nested inside a second one, so every sub-screen inherits
 * the exact same 20dp padding and 12dp vertical rhythm [SettingsScreen] itself uses, with no
 * way for one section to quietly drift to different spacing.
 *
 * An unknown [sectionId] (only reachable if a saved route ever outlives [SettingsSection]
 * itself, the same concern [ch.kevinjordil.helion.ui.HelionNavHost] already documents for
 * its own metric route) falls back to leaving the sub-screen immediately rather than
 * rendering nothing.
 */
@Composable
fun SettingsSectionScreen(container: AppContainer, sectionId: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    val section = SettingsSection.entries.firstOrNull { it.id == sectionId }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsBackLink(onBack)

        if (section == null) {
            LaunchedEffect(sectionId) { onBack() }
            return@Column
        }

        Text(stringResource(section.titleRes).uppercase(), style = HelionType.label, color = colors.textSecondary)

        when (section) {
            SettingsSection.SOURCE -> SourceSettingsSection(container)
            SettingsSection.PROFILE -> ProfileSettingsSection(container)
            SettingsSection.GOALS -> GoalsSettingsSection(container)
            SettingsSection.STRAVA -> CustomServerSettingsSection(container)
            SettingsSection.HEALTH_CONNECT -> HealthConnectSettingsSection(container)
            SettingsSection.NOTIFICATIONS -> NotificationsSettingsSection(container)
            SettingsSection.MAINTENANCE -> ArchiveReanalysisSection(container)
            SettingsSection.ABOUT -> AboutSettingsSection()
        }
    }
}

/** The one back-to-the-list link every [SettingsSectionScreen] opens with. */
@Composable
private fun SettingsBackLink(onBack: () -> Unit) {
    val colors = HelionThemeTokens.colors
    Text(
        stringResource(R.string.action_back),
        style = HelionType.label,
        color = colors.accentViolet,
        modifier = Modifier.clickable(onClick = onBack),
    )
}
