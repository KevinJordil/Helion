package ch.kevinjordil.helion.ui.sleep

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionType

/**
 * Sommeil's history list -- split out of `SleepScreen.kt` once the row itself grew from a
 * bare "date · duration" line into a small chart of its own (this file's whole reason to
 * exist): a per-night stage-composition bar, so a short night or one poor in deep sleep is
 * visible while scanning the list, not only after opening it. `SleepScreen.kt` keeps the
 * selected night's own detail card; this file owns everything about comparing nights to
 * each other in the list below it.
 */
private const val REFERENCE_MAX_DURATION_MINUTES = 600L

/**
 * How wide [durationMinutes] should draw its history-row bar, as a fraction of the row's
 * own bar track -- `0f..1f`, clamped so a night longer than [referenceMaxMinutes] simply
 * fills the track rather than overflowing it. [referenceMaxMinutes] (ten hours by default)
 * is not a plausible ceiling so much as a fixed ruler: every row measures against the same
 * one, which is what makes a short night's bar visibly shorter than a long night's, rather
 * than every row separately stretching to fill its own track and erasing the comparison
 * this bar exists to show.
 */
internal fun nightBarWidthFraction(durationMinutes: Long, referenceMaxMinutes: Long = REFERENCE_MAX_DURATION_MINUTES): Float {
    if (referenceMaxMinutes <= 0L) return 0f
    return (durationMinutes.toFloat() / referenceMaxMinutes.toFloat()).coerceIn(0f, 1f)
}

/**
 * One row of the history list: weekday+date, a compact composition bar, and the duration
 * -- tapping it still selects that night, exactly as before. The bar's own width (against
 * a fixed reference, see [nightBarWidthFraction]) makes a short night visibly shorter, and
 * its segments (see [nightStageComposition]) show that night's own mix of stages, both at
 * a glance and without opening the row.
 *
 * A night whose stages could not be classified at all ([SleepPhaseSource.NotEstimable])
 * draws a flat, uncoloured bar instead of a composition -- see [StageCompositionBar]'s
 * kdoc for why that, and not some fabricated even split, is the honest way to show "no
 * stage data for this night". A night whose stages are [SleepPhaseSource.Estimated] still
 * gets full colour (a guess is still informative), but carries the same "estimé" tag the
 * detail card uses, so it is never mistaken for the device's own measurement.
 */
@Composable
internal fun HistoryRow(episode: SleepEpisode, onClick: () -> Unit) {
    val colors = HelionThemeTokens.colors
    val hours = episode.durationAsleepMinutes / 60
    val minutes = episode.durationAsleepMinutes % 60
    val weekdays = stringArrayResource(R.array.weekday_short).toList()

    val phaseSource = remember(episode) { resolveSleepPhases(episode) }
    val composition = remember(phaseSource) {
        when (phaseSource) {
            is SleepPhaseSource.Measured -> nightStageComposition(phaseSource.minutes)
            is SleepPhaseSource.Estimated -> nightStageComposition(phaseSource.minutes)
            SleepPhaseSource.NotEstimable -> null
        }
    }
    val barFraction = nightBarWidthFraction(episode.durationAsleepMinutes)
    val phaseColor = phaseColors(colors)

    val tag = when {
        episode.isInProgress -> stringResource(R.string.sleep_history_in_progress_tag) to colors.accentAmber
        episode.hasDataGap -> stringResource(R.string.sleep_history_incomplete_tag) to colors.accentAmber
        phaseSource is SleepPhaseSource.Estimated -> stringResource(R.string.sleep_history_estimated_tag) to colors.textTertiary
        else -> null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Button)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            weekdayDateText(episode.date, weekdays),
            style = HelionType.body,
            color = colors.textSecondary,
            modifier = Modifier.width(104.dp),
        )
        StageCompositionBar(
            composition = composition,
            barFraction = barFraction,
            phaseColor = phaseColor,
            trackColor = colors.divider,
            noDataColor = colors.textTertiary,
            modifier = Modifier.weight(1f).height(10.dp),
        )
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(96.dp)) {
            Text(
                stringResource(R.string.sleep_duration_format, hours.toInt(), minutes.toInt()),
                style = HelionType.body,
                color = colors.textPrimary,
            )
            tag?.let { (text, color) -> Text(text, style = HelionType.labelSmall, color = color) }
        }
    }
}

/**
 * The bar itself: a full-width, low-alpha track (so every row's full reference length is
 * visible, not just the part a given night fills) with the night's own duration-scaled,
 * stage-coloured fill drawn on top.
 *
 * [composition] null (an unclassifiable night, see [SleepPhaseSource.NotEstimable]) draws
 * the duration-scaled fill as a flat [noDataColor] block instead of colour segments --
 * this is the "read as such" requirement: a night with genuinely no stage data must never
 * render as if it had a bland, evenly-mixed composition, which a fabricated fallback split
 * would look exactly like. The absence of colour *is* the signal.
 */
@Composable
private fun StageCompositionBar(
    composition: List<StageComposition>?,
    barFraction: Float,
    phaseColor: Map<SleepPhase, Color>,
    trackColor: Color,
    noDataColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val cornerRadius = CornerRadius(size.height / 2f)

        drawRoundRect(
            color = trackColor.copy(alpha = 0.5f),
            topLeft = Offset.Zero,
            size = Size(size.width, size.height),
            cornerRadius = cornerRadius,
        )

        val barWidth = barFraction * size.width
        if (barWidth <= 0f) return@Canvas

        if (composition == null) {
            drawRoundRect(
                color = noDataColor.copy(alpha = 0.6f),
                topLeft = Offset.Zero,
                size = Size(barWidth, size.height),
                cornerRadius = cornerRadius,
            )
            return@Canvas
        }

        // Clipped once to the filled portion's own rounded outline, then drawn as plain
        // adjoining rectangles -- only the whole bar's outline needs to be a pill, not
        // each internal segment boundary.
        val fillPath = Path().apply { addRoundRect(RoundRect(0f, 0f, barWidth, size.height, cornerRadius)) }
        clipPath(fillPath) {
            var left = 0f
            composition.forEach { segment ->
                val width = segment.fraction * barWidth
                drawRect(
                    color = phaseColor.getValue(segment.phase),
                    topLeft = Offset(left, 0f),
                    size = Size(width, size.height),
                )
                left += width
            }
        }
    }
}
