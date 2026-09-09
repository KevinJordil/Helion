package ch.kevinjordil.helion.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.activity.ActivityBadge
import ch.kevinjordil.helion.ui.theme.HelionColors
import ch.kevinjordil.helion.ui.theme.HelionCornerRadiusSmall
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType

/** The glyph that carries a badge's meaning before its words are read. */
private fun badgeEmoji(badge: ActivityBadge): String = when (badge) {
    is ActivityBadge.FirstInSport -> "🌱"
    is ActivityBadge.Milestone -> "🎯"
    ActivityBadge.LongestEver -> "⏱️"
    is ActivityBadge.LongestInSport -> "⏱️"
    ActivityBadge.MaxHeartRate -> "❤️‍🔥"
    ActivityBadge.HighestIntensity -> "🔥"
}

/** A badge's own colour: the sport's where it names one, otherwise the app's accent. */
@Composable
private fun badgeColor(badge: ActivityBadge, colors: HelionColors): Color = when (badge) {
    is ActivityBadge.FirstInSport -> colors.sportColor(badge.sport.category)
    is ActivityBadge.LongestInSport -> colors.sportColor(badge.sport.category)
    else -> colors.accentViolet
}

@Composable
private fun badgeLabel(badge: ActivityBadge): String = when (badge) {
    is ActivityBadge.FirstInSport -> stringResource(R.string.badge_first_in_sport, stringResource(sportLabelRes(badge.sport)))
    is ActivityBadge.Milestone -> stringResource(R.string.badge_milestone, badge.count)
    ActivityBadge.LongestEver -> stringResource(R.string.badge_longest_ever)
    is ActivityBadge.LongestInSport -> stringResource(R.string.badge_longest_in_sport, stringResource(sportLabelRes(badge.sport)))
    ActivityBadge.MaxHeartRate -> stringResource(R.string.badge_max_heart_rate)
    ActivityBadge.HighestIntensity -> stringResource(R.string.badge_highest_intensity)
}

/**
 * One badge, as a chip: its glyph and its words together. Never the glyph alone -- an emoji
 * is a hint, not a label, and a badge nobody can name is decoration.
 *
 * The chip is washed in the badge's own colour rather than filled with it, so a card
 * carrying three badges does not turn into three solid blocks; the text keeps the app's own
 * ink and its contrast never depends on which badge it is.
 */
@Composable
fun BadgeChip(badge: ActivityBadge, modifier: Modifier = Modifier) {
    val colors = HelionThemeTokens.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(HelionCornerRadiusSmall))
            .background(badgeColor(badge, colors).copy(alpha = 0.20f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(badgeEmoji(badge), style = HelionType.labelSmall)
        Text(badgeLabel(badge), style = HelionType.labelSmall, color = colors.textPrimary)
    }
}

/**
 * Every badge an activity has earned, wrapped onto as many lines as it takes. A [FlowRow]
 * rather than a fixed grid: badge labels are sentences of very different lengths ("🎯 10e
 * activité" against "⏱️ Ta plus longue séance en badminton"), and forcing them into equal
 * columns would either clip the long ones or waste the row on the short ones.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BadgeChips(badges: List<ActivityBadge>, modifier: Modifier = Modifier) {
    if (badges.isEmpty()) return
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        badges.forEach { BadgeChip(it) }
    }
}
