package ch.kevinjordil.helion.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.AppContainer
import ch.kevinjordil.helion.BuildConfig
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.ui.theme.HelionCardSpacing
import ch.kevinjordil.helion.ui.theme.HelionIcons
import ch.kevinjordil.helion.ui.theme.HelionScreenEdgeMargin
import ch.kevinjordil.helion.ui.theme.HelionScreenHeader
import ch.kevinjordil.helion.ui.theme.HelionSectionLabel
import ch.kevinjordil.helion.ui.theme.HelionSurface
import ch.kevinjordil.helion.ui.theme.HelionSurfacePadding
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionTopBar
import ch.kevinjordil.helion.ui.theme.HelionType
import ch.kevinjordil.helion.ui.theme.HorizontalRule
import ch.kevinjordil.helion.ui.theme.IconTile
import ch.kevinjordil.helion.ui.theme.StatusDot
import ch.kevinjordil.helion.ui.theme.helionRow

/**
 * The sections of Réglages, each opened as its own screen from the list. [id] is the route
 * suffix, so it must stay stable across versions (it survives process death in the back stack).
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

/** How a section's state reads on the list: working, needing setup, or simply off. */
private enum class EntryState { OK, ATTENTION, NEUTRAL }

/**
 * Réglages: every section grouped by what it is about -- daily use, data and connections,
 * the app itself -- as rows with a glyph, the section's name and its current state at a
 * glance. A connection that works carries a green dot; one the app cannot work without
 * (no export file) carries the attention colour.
 */
@Composable
fun SettingsScreen(container: AppContainer, onOpenSection: (String) -> Unit, modifier: Modifier = Modifier) {
    val groups = listOf(
        R.string.settings_group_personal to listOf(SettingsSection.PROFILE, SettingsSection.GOALS, SettingsSection.NOTIFICATIONS),
        R.string.settings_group_connections to listOf(SettingsSection.SOURCE, SettingsSection.STRAVA, SettingsSection.HEALTH_CONNECT),
        R.string.settings_group_application to listOf(SettingsSection.MAINTENANCE, SettingsSection.ABOUT),
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(HelionCardSpacing),
    ) {
        HelionScreenHeader(
            title = stringResource(R.string.tab_settings),
            subtitle = {
                Text(stringResource(R.string.settings_subtitle), style = HelionType.bodySmall, color = HelionThemeTokens.colors.textSecondary)
            },
        )
        groups.forEach { (titleRes, sections) ->
            Column(
                modifier = Modifier.padding(horizontal = HelionScreenEdgeMargin).padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HelionSectionLabel(stringResource(titleRes))
                HelionSurface(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
                    sections.forEachIndexed { index, section ->
                        if (index > 0) HorizontalRule(Modifier.padding(start = 62.dp))
                        val (value, state) = settingsEntryPreview(section, container)
                        SettingsEntryRow(
                            label = stringResource(section.titleRes),
                            value = value,
                            state = state,
                            icon = settingsIcon(section),
                            onClick = { onOpenSection(section.id) },
                        )
                    }
                }
            }
        }
    }
}

private fun settingsIcon(section: SettingsSection): ImageVector = when (section) {
    SettingsSection.PROFILE -> HelionIcons.User
    SettingsSection.GOALS -> HelionIcons.Target
    SettingsSection.NOTIFICATIONS -> HelionIcons.Bell
    SettingsSection.SOURCE -> HelionIcons.Link
    SettingsSection.STRAVA -> HelionIcons.Upload
    SettingsSection.HEALTH_CONNECT -> HelionIcons.Heart
    SettingsSection.MAINTENANCE -> HelionIcons.Wrench
    SettingsSection.ABOUT -> HelionIcons.Info
}

@Composable
private fun SettingsEntryRow(label: String, value: String, state: EntryState, icon: ImageVector, onClick: () -> Unit) {
    val colors = HelionThemeTokens.colors
    val dot: Color? = when (state) {
        EntryState.OK -> colors.positive
        EntryState.ATTENTION -> colors.attention
        EntryState.NEUTRAL -> null
    }
    Row(
        modifier = Modifier
            .helionRow(onClick)
            .heightIn(min = 62.dp)
            .padding(horizontal = HelionSurfacePadding, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint = colors.textSecondary, size = 34.dp, iconSize = 18.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = HelionType.body, color = colors.textPrimary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                dot?.let { StatusDot(it) }
                Text(
                    value,
                    style = HelionType.bodySmall,
                    color = if (state == EntryState.ATTENTION) colors.attention else colors.textSecondary,
                )
            }
        }
        Icon(HelionIcons.ChevronRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
    }
}

/**
 * The second line of a section's row: its state in a word or two, read from the same
 * stores the section itself edits, so the list never says something the section disagrees with.
 */
@Composable
private fun settingsEntryPreview(section: SettingsSection, container: AppContainer): Pair<String, EntryState> = when (section) {
    SettingsSection.SOURCE -> if (container.exportLocation.uri != null) {
        stringResource(R.string.settings_entry_source_configured) to EntryState.OK
    } else {
        stringResource(R.string.export_location_none) to EntryState.ATTENTION
    }
    SettingsSection.PROFILE -> if (container.profile.isComplete) {
        stringResource(R.string.settings_entry_profile_complete) to EntryState.NEUTRAL
    } else {
        stringResource(R.string.settings_entry_profile_incomplete) to EntryState.NEUTRAL
    }
    SettingsSection.GOALS -> stringResource(R.string.settings_entry_goals_value, container.stepsGoal.value) to EntryState.NEUTRAL
    SettingsSection.STRAVA -> if (container.customServerConfig.isConfigured) {
        stringResource(R.string.settings_status_configured) to EntryState.OK
    } else {
        stringResource(R.string.settings_status_not_configured) to EntryState.NEUTRAL
    }
    SettingsSection.HEALTH_CONNECT -> if (container.healthConnectConfig.enabled) {
        stringResource(R.string.settings_status_enabled) to EntryState.OK
    } else {
        stringResource(R.string.settings_status_disabled) to EntryState.NEUTRAL
    }
    SettingsSection.NOTIFICATIONS -> if (container.notificationPreference.enabled) {
        stringResource(R.string.settings_status_enabled) to EntryState.OK
    } else {
        stringResource(R.string.settings_status_disabled) to EntryState.NEUTRAL
    }
    SettingsSection.MAINTENANCE -> stringResource(R.string.settings_entry_maintenance_value) to EntryState.NEUTRAL
    SettingsSection.ABOUT -> BuildConfig.BUILD_STAMP to EntryState.NEUTRAL
}

/** One section of Réglages on its own screen, under a top bar that returns to the list. */
@Composable
fun SettingsSectionScreen(container: AppContainer, sectionId: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val section = SettingsSection.entries.firstOrNull { it.id == sectionId }
    if (section == null) {
        LaunchedEffect(sectionId) { onBack() }
        return
    }
    Column(modifier = modifier.fillMaxSize()) {
        HelionTopBar(title = stringResource(section.titleRes), onBack = onBack, leadingIcon = settingsIcon(section))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HelionScreenEdgeMargin)
                .padding(top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(HelionCardSpacing),
        ) {
            HelionSurface(
                modifier = Modifier.fillMaxWidth(),
                padding = PaddingValues(HelionSurfacePadding),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
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
    }
}
