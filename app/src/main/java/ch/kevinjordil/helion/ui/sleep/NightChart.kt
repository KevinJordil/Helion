package ch.kevinjordil.helion.ui.sleep

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.kevinjordil.helion.R
import ch.kevinjordil.helion.ui.metric.ChartOverlay
import ch.kevinjordil.helion.ui.metric.ChartStyle
import ch.kevinjordil.helion.ui.metric.DetailChart
import ch.kevinjordil.helion.ui.metric.MetricCatalog
import ch.kevinjordil.helion.ui.metric.metricColor
import ch.kevinjordil.helion.ui.metric.Reading
import ch.kevinjordil.helion.ui.metric.chartTimeMarkers
import ch.kevinjordil.helion.ui.metric.formatAxisTimestamp
import ch.kevinjordil.helion.ui.theme.HelionMotion
import ch.kevinjordil.helion.ui.theme.HelionStatRow
import ch.kevinjordil.helion.ui.theme.HelionThemeTokens
import ch.kevinjordil.helion.ui.theme.HelionToggleChip
import ch.kevinjordil.helion.ui.theme.HelionType
import ch.kevinjordil.helion.ui.theme.StatCell
import ch.kevinjordil.helion.ui.theme.rememberReducedMotion
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private val NIGHT_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** The lane-name column on the left of the hypnogram; the night's heart-rate chart is indented by the same width so both share one time axis. */
internal val LANE_LABEL_WIDTH: Dp = 68.dp

/** The share column on the right, the same width as the heart-rate chart's value axis. */
private val LANE_SHARE_WIDTH: Dp = 40.dp

private val LANE_HEIGHT = 26.dp
private val LANE_BLOCK_HEIGHT = 14.dp
private val TIME_AXIS_HEIGHT = 18.dp
private val NIGHT_HR_HEIGHT = 168.dp

/** Lanes top to bottom: the classic hypnogram order, awake at the top and deep sleep at the bottom. */
private val LANES = listOf(SleepPhase.AWAKE, SleepPhase.REM, SleepPhase.LIGHT, SleepPhase.DEEP)

/**
 * The night's hypnogram: one lane per phase, each continuous stretch of a phase drawn as a
 * block on its lane and joined to the next by a thin riser, so the night reads as a single
 * stepped line through the four depths. Times along the bottom; each lane's share of the
 * night on the right.
 *
 * Drawn from the minute-exact phase runs ([phaseSegments]), not from buckets, so a ten-minute
 * stretch of REM is exactly that wide. Dragging across it moves the shared [cursor], which
 * the heart-rate chart below follows -- and dragging there moves this one.
 */
@Composable
internal fun Hypnogram(
    episode: SleepEpisode,
    segments: List<PhaseSegment>,
    breakdown: Map<SleepPhase, Int>,
    cursor: Long?,
    onCursor: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HelionThemeTokens.colors
    val phaseColor = phaseColors(colors)
    val measurer = rememberTextMeasurer()
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val reduced = rememberReducedMotion()
    val reveal = remember(episode) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(episode) {
        if (!reduced) reveal.animateTo(1f, tween(HelionMotion.LONG, easing = HelionMotion.Emphasized))
    }
    val start = episode.fellAsleepAt
    val end = episode.wokeAt + 60
    val total = breakdown.values.sum().coerceAtLeast(1)
    var widthPx by remember { mutableFloatStateOf(0f) }

    fun timeAt(x: Float): Long = start + ((x / widthPx.coerceAtLeast(1f)).coerceIn(0f, 1f) * (end - start)).toLong()

    Row(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.width(LANE_LABEL_WIDTH)) {
            LANES.forEach { lane ->
                Box(modifier = Modifier.heightIn(min = LANE_HEIGHT), contentAlignment = Alignment.CenterStart) {
                    Text(stringResource(phaseLabelRes(lane)), style = HelionType.labelSmall, color = colors.textSecondary)
                }
            }
        }
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(LANE_HEIGHT * LANES.size + TIME_AXIS_HEIGHT)
                .semantics { contentDescription = "hypnogram" }
                .pointerInput(episode) {
                    detectTapGestures(onTap = { offset -> onCursor(if (cursor != null) null else timeAt(offset.x)) })
                }
                .pointerInput(episode) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var scrubbing = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.pressed } ?: break
                            if (!scrubbing) {
                                val dx = change.position.x - down.position.x
                                val dy = change.position.y - down.position.y
                                if (abs(dx) > touchSlop && abs(dx) > abs(dy)) scrubbing = true else if (abs(dy) > touchSlop) break
                            }
                            if (scrubbing) {
                                if (change.positionChange() != Offset.Zero) change.consume()
                                onCursor(timeAt(change.position.x))
                            }
                        }
                    }
                },
        ) {
            widthPx = size.width
            val laneH = LANE_HEIGHT.toPx()
            val blockH = LANE_BLOCK_HEIGHT.toPx()
            val span = (end - start).toFloat().coerceAtLeast(1f)
            fun xOf(t: Long) = ((t - start) / span) * size.width
            fun laneCentre(phase: SleepPhase) = laneH * LANES.indexOf(phase) + laneH / 2f

            // Lane guides.
            LANES.forEach { lane ->
                val y = laneCentre(lane)
                drawLine(colors.hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }

            clipRect(right = size.width * reveal.value) {
                // Risers first, so the blocks sit on top of them.
                segments.zipWithNext().forEach { (a, b) ->
                    if (a.phase != b.phase && b.startTimestamp - a.endTimestamp <= 120) {
                        val x = xOf(b.startTimestamp)
                        drawLine(colors.textTertiary.copy(alpha = 0.55f), Offset(x, laneCentre(a.phase)), Offset(x, laneCentre(b.phase)), strokeWidth = 1.dp.toPx())
                    }
                }
                segments.forEach { segment ->
                    val left = xOf(segment.startTimestamp)
                    val right = xOf(segment.endTimestamp).coerceAtLeast(left + 1.5f)
                    val y = laneCentre(segment.phase) - blockH / 2f
                    drawRoundRect(
                        phaseColor.getValue(segment.phase),
                        Offset(left, y),
                        Size(right - left, blockH),
                        CornerRadius(minOf(3.dp.toPx(), (right - left) / 2f)),
                    )
                }
            }

            // Time axis.
            val axisTop = laneH * LANES.size
            val axisStyle = HelionType.axisLabel.copy(color = colors.textTertiary)
            var lastRight = Float.NEGATIVE_INFINITY
            chartTimeMarkers(start, end).forEach { t ->
                val x = xOf(t)
                drawLine(colors.divider, Offset(x, axisTop), Offset(x, axisTop + 3.dp.toPx()), strokeWidth = 1f)
                val label = measurer.measure(formatAxisTimestamp(t, end - start), axisStyle)
                val left = (x - label.size.width / 2f).coerceIn(0f, (size.width - label.size.width).coerceAtLeast(0f))
                if (left >= lastRight + 6.dp.toPx()) {
                    drawText(label, topLeft = Offset(left, size.height - label.size.height))
                    lastRight = left + label.size.width
                }
            }

            cursor?.let { t ->
                val x = xOf(t).coerceIn(0f, size.width)
                drawLine(colors.textPrimary.copy(alpha = 0.8f), Offset(x, 0f), Offset(x, axisTop), strokeWidth = 1.dp.toPx())
            }
        }
        Column(modifier = Modifier.width(LANE_SHARE_WIDTH)) {
            LANES.forEach { lane ->
                Box(modifier = Modifier.fillMaxWidth().heightIn(min = LANE_HEIGHT), contentAlignment = Alignment.CenterEnd) {
                    val share = ((breakdown[lane] ?: 0) * 100f / total).roundToInt()
                    Text(stringResource(R.string.sleep_phase_share, share), style = HelionType.labelSmall, color = colors.textTertiary, textAlign = TextAlign.End)
                }
            }
        }
    }
}

/**
 * The night's heart rate, on the same time axis as the hypnogram above it, with the
 * respiratory-rate and movement overlays the owner can switch on, and the night's
 * minimum, average and maximum.
 */
@Composable
internal fun NightHeartRate(
    episode: SleepEpisode,
    showRespiratory: Boolean,
    onShowRespiratoryChange: (Boolean) -> Unit,
    showMovement: Boolean,
    onShowMovementChange: (Boolean) -> Unit,
    cursor: Long?,
    onCursor: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HelionThemeTokens.colors
    val heartRate = remember(episode) {
        episode.minutes.mapNotNull { m -> m.heartRate?.let { Reading(m.timestamp, it.toDouble()) } }.sortedBy { it.timestamp }
    }
    if (heartRate.size < 2) return
    val movement = remember(episode) {
        episode.minutes.mapNotNull { m -> m.intensity?.let { Reading(m.timestamp, it.toDouble()) } }.sortedBy { it.timestamp }
    }
    val respiratory = episode.respiratoryRateReadings
    val bpm = stringResource(R.string.unit_bpm)
    val hue = colors.metricColor(MetricCatalog.byId("heart_rate")!!)
    val overlays = buildList {
        if (showRespiratory && respiratory.size >= 2) add(ChartOverlay(respiratory, colors.textPrimary, floatArrayOf(10f, 6f)))
        if (showMovement && movement.size >= 2) add(ChartOverlay(movement, colors.textSecondary, floatArrayOf(3f, 5f)))
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(LANE_LABEL_WIDTH))
            DetailChart(
                readings = heartRate,
                domainStart = episode.fellAsleepAt,
                domainEnd = episode.wokeAt + 60,
                style = ChartStyle.LINE,
                color = hue,
                unit = bpm,
                formatValue = { "%.0f".format(it) },
                formatTime = { NIGHT_CLOCK.format(Instant.ofEpochSecond(it)) },
                formatAxisTime = { t, span -> formatAxisTimestamp(t, span) },
                description = stringResource(R.string.sleep_night_heart_rate_title),
                overlays = overlays,
                externalCursor = cursor,
                showReadout = false,
                onScrub = { reading -> onCursor(reading?.timestamp) },
                minVisibleSeconds = 1_800L,
                modifier = Modifier.weight(1f).height(NIGHT_HR_HEIGHT),
            )
        }
        if (respiratory.size >= 2 || movement.size >= 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (respiratory.size >= 2) {
                    HelionToggleChip(stringResource(R.string.sleep_overlay_respiratory), showRespiratory, onShowRespiratoryChange, keyColor = colors.textPrimary)
                }
                if (movement.size >= 2) {
                    HelionToggleChip(stringResource(R.string.sleep_overlay_movement), showMovement, onShowMovementChange, keyColor = colors.textSecondary)
                }
            }
        }
        if (overlays.isNotEmpty()) {
            Text(stringResource(R.string.sleep_overlay_scale_note), style = HelionType.labelSmall, color = colors.textTertiary)
        }
        HelionStatRow(
            listOf(
                StatCell(stringResource(R.string.stat_min), "%.0f".format(heartRate.minOf { it.value }), bpm),
                StatCell(stringResource(R.string.stat_average), "%.0f".format(heartRate.sumOf { it.value } / heartRate.size), bpm),
                StatCell(stringResource(R.string.stat_max), "%.0f".format(heartRate.maxOf { it.value }), bpm),
            ),
        )
    }
}

/** "02:14 · Léger · 54 bpm" for the instant under the shared cursor. */
@Composable
internal fun cursorReadout(episode: SleepEpisode, segments: List<PhaseSegment>, time: Long): String {
    val clock = NIGHT_CLOCK.format(Instant.ofEpochSecond(time))
    val phase = segments.firstOrNull { time in it.startTimestamp until it.endTimestamp }?.phase
    val heartRate = episode.minutes.filter { it.heartRate != null }.minByOrNull { abs(it.timestamp - time) }
        ?.takeIf { abs(it.timestamp - time) <= 300 }?.heartRate
    return listOfNotNull(
        clock,
        phase?.let { stringResource(phaseLabelRes(it)) },
        heartRate?.let { "$it ${stringResource(R.string.unit_bpm)}" },
    ).joinToString(" · ")
}

/** One continuous run of a single phase, from its first minute to the end of its last. */
internal data class PhaseSegment(val startTimestamp: Long, val endTimestamp: Long, val phase: SleepPhase)

/**
 * Collapses a phase source's per-minute track into continuous [PhaseSegment]s: consecutive
 * minutes of the same phase, no more than a minute apart, join one segment; anything else
 * starts a new one. Empty for a night whose phases cannot be estimated.
 */
internal fun phaseSegments(source: SleepPhaseSource): List<PhaseSegment> {
    val minutes = when (source) {
        is SleepPhaseSource.Measured -> source.minutes
        is SleepPhaseSource.Estimated -> source.minutes
        SleepPhaseSource.NotEstimable -> return emptyList()
    }.sortedBy { it.timestamp }
    if (minutes.isEmpty()) return emptyList()

    val segments = mutableListOf<PhaseSegment>()
    var runStart = minutes.first().timestamp
    var runEnd = minutes.first().timestamp
    var runPhase = minutes.first().phase

    for (minute in minutes.drop(1)) {
        if (minute.phase == runPhase && minute.timestamp - runEnd <= 60L) {
            runEnd = minute.timestamp
        } else {
            segments.add(PhaseSegment(runStart, runEnd + 60, runPhase))
            runStart = minute.timestamp
            runEnd = minute.timestamp
            runPhase = minute.phase
        }
    }
    segments.add(PhaseSegment(runStart, runEnd + 60, runPhase))
    return segments
}
