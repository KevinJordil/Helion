package ch.kevinjordil.helion.activity

import ch.kevinjordil.helion.store.Slot
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** One concrete instant range a [Slot] resolves to. Both fields are Unix seconds. */
data class SlotOccurrence(val start: Long, val end: Long)

/**
 * Resolves [slot] to its concrete occurrences that overlap `[from, to]` (Unix seconds), in
 * [zone]. Returns an empty list for an [Slot.active] == false slot: a suspended slot
 * generates nothing.
 *
 * The slot's start and end are wall-clock local times, not instants, so each occurrence is
 * anchored to a calendar date -- whichever date in range falls on [Slot.dayOfWeek] -- and
 * turned into an instant via [java.time.LocalDateTime.atZone], the same way
 * [ch.kevinjordil.helion.ui.metric.MetricReader.dayStart] resolves a calendar day. That is
 * deliberate: adding a fixed number of seconds to that date's midnight *instant* would walk
 * straight across any DST transition that happened earlier the same day and land on the
 * wrong wall-clock hour (a whole hour off for half the year, for anyone whose slot is in
 * the evening). Resolving the wall-clock date+time pair through the zone instead keeps
 * 20:00 meaning 20:00 on both sides of the transition. For the rare local time that does not
 * exist (the skipped hour of a spring-forward) or is ambiguous (the repeated hour of a
 * fall-back), this defers to [java.time.LocalDateTime]'s own default resolution (shifted
 * forward past a gap, earlier offset for an overlap) -- exactly what
 * `LocalDateTime.atZone` already does, so nothing extra is done here.
 *
 * A slot whose [Slot.endSecondOfDay] is not strictly after [Slot.startSecondOfDay] is read
 * as crossing midnight: its occurrence ends on the calendar day after the one its start
 * falls on.
 */
fun occurrencesBetween(slot: Slot, from: Long, to: Long, zone: ZoneId): List<SlotOccurrence> {
    if (!slot.active) return emptyList()

    val startDate = Instant.ofEpochSecond(from).atZone(zone).toLocalDate().minusDays(1)
    val endDate = Instant.ofEpochSecond(to).atZone(zone).toLocalDate()

    val occurrences = mutableListOf<SlotOccurrence>()
    var date = startDate
    while (!date.isAfter(endDate)) {
        if (date.dayOfWeek == slot.dayOfWeek) {
            val start = date.atTime(LocalTime.ofSecondOfDay(slot.startSecondOfDay.toLong()))
                .atZone(zone)
                .toEpochSecond()
            val endDay = if (slot.endSecondOfDay <= slot.startSecondOfDay) date.plusDays(1) else date
            val end = endDay.atTime(LocalTime.ofSecondOfDay(slot.endSecondOfDay.toLong()))
                .atZone(zone)
                .toEpochSecond()
            if (end > from && start < to) {
                occurrences.add(SlotOccurrence(start, end))
            }
        }
        date = date.plusDays(1)
    }
    return occurrences
}
