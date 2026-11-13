package ch.nthnbch.kalendar.ical

import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.recur.RecurrenceRule
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

object Recurrence {

    /** Safety net against pathological rules (e.g. SECONDLY without end). */
    private const val MAX_STEPS = 20_000

    /**
     * Expands a resource into the occurrences that overlap `[from, to)`, in [deviceZone].
     * Overrides replace the occurrence they point at; cancelled and excluded ones are dropped.
     */
    fun expand(resource: EventResource, from: Instant, to: Instant, deviceZone: ZoneId): List<Occurrence> {
        val out = ArrayList<Occurrence>()
        val overridden = resource.events.mapNotNull { it.recurrenceKey }.toSet()

        for (event in resource.events) {
            if (event.cancelled) continue
            if (event.recurrenceKey != null || !event.isRecurring) {
                occurrence(resource, event, event.start, from, to, deviceZone)?.let(out::add)
                continue
            }
            val excluded = event.exdates.mapTo(HashSet()) { event.keyOf(it, deviceZone) }
            for (start in starts(event, from, to, deviceZone)) {
                val key = event.keyOf(start, deviceZone)
                if (key in excluded || key in overridden) continue
                occurrence(resource, event, start, from, to, deviceZone)?.let(out::add)
            }
        }
        return out
    }

    /** Inverse of [VEvent.keyOf]: the local start, in the master's zone, of the occurrence with that key. */
    fun originalStartOf(master: VEvent, key: Long, deviceZone: ZoneId): LocalDateTime =
        if (master.allDay) java.time.LocalDate.ofEpochDay(key).atStartOfDay()
        else Instant.ofEpochSecond(key).atZone(master.zone ?: deviceZone).toLocalDateTime()

    private fun occurrence(
        resource: EventResource,
        event: VEvent,
        localStart: LocalDateTime,
        from: Instant,
        to: Instant,
        deviceZone: ZoneId,
    ): Occurrence? {
        val start: ZonedDateTime
        val end: ZonedDateTime
        if (event.allDay || event.zone == null) {
            // Floating: same wall-clock time wherever the device is.
            start = localStart.atZone(deviceZone)
            end = localStart.plus(Duration.between(event.start, event.end)).atZone(deviceZone)
        } else {
            val zoned = localStart.atZone(event.zone)
            start = zoned.withZoneSameInstant(deviceZone)
            // Keep the wall-clock length in the event's zone so a 9:00–10:00 meeting stays one hour across DST.
            end = localStart.plus(Duration.between(event.start, event.end)).atZone(event.zone).withZoneSameInstant(deviceZone)
        }
        val startInstant = start.toInstant()
        val endInstant = end.toInstant()
        val overlaps = startInstant < to && (endInstant > from || (endInstant == startInstant && !startInstant.isBefore(from)))
        return if (overlaps) Occurrence(event, resource.href, resource.calendarUrl, start, end, localStart) else null
    }

    /** Local start times (in the event's zone) of the occurrences that may overlap the window. */
    private fun starts(event: VEvent, from: Instant, to: Instant, deviceZone: ZoneId): List<LocalDateTime> {
        val zone = event.zone ?: deviceZone
        val length = Duration.between(event.start, event.end)
        val windowStart = from.minus(length).atZone(zone).toLocalDateTime()
        val windowEnd = to.atZone(zone).toLocalDateTime()
        val result = sortedSetOf<LocalDateTime>()

        event.rdates.filterTo(result) { !it.isBefore(windowStart) && it.isBefore(windowEnd) }

        val text = event.rrule
        if (text == null) {
            if (!event.start.isBefore(windowStart) && event.start.isBefore(windowEnd)) result.add(event.start)
            return result.toList()
        }

        try {
            val rule = RecurrenceRule(text, RecurrenceRule.RfcMode.RFC5545_LAX)
            val first = toDateTime(event, event.start)
            alignUntil(rule, first, event)
            val iterator = rule.iterator(first)
            if (windowStart.isAfter(event.start)) iterator.fastForward(toDateTime(event, windowStart))
            var steps = 0
            while (iterator.hasNext() && steps++ < MAX_STEPS) {
                val next = iterator.nextDateTime()
                val local = LocalDateTime.of(next.year, next.month + 1, next.dayOfMonth, next.hours, next.minutes, next.seconds)
                if (!local.isBefore(windowEnd)) break
                if (!local.isBefore(windowStart)) result.add(local)
            }
        } catch (e: Exception) {
            // An unreadable rule must not hide the event altogether: show at least its first occurrence.
            if (!event.start.isBefore(windowStart) && event.start.isBefore(windowEnd)) result.add(event.start)
        }
        return result.toList()
    }

    private fun toDateTime(event: VEvent, local: LocalDateTime): DateTime = when {
        event.allDay -> DateTime(local.year, local.monthValue - 1, local.dayOfMonth)
        event.zone == null -> DateTime(local.year, local.monthValue - 1, local.dayOfMonth, local.hour, local.minute, local.second)
        else -> DateTime(
            TimeZone.getTimeZone(event.zone.id),
            local.year, local.monthValue - 1, local.dayOfMonth, local.hour, local.minute, local.second,
        )
    }

    /**
     * The library insists that UNTIL has the same kind (date / floating / zoned) as the start,
     * but real-world files mix them freely, so UNTIL is rewritten to match.
     */
    private fun alignUntil(rule: RecurrenceRule, first: DateTime, event: VEvent) {
        val until = rule.until ?: return
        when {
            first.isAllDay && !until.isAllDay ->
                rule.until = DateTime(until.year, until.month, until.dayOfMonth)
            !first.isAllDay && until.isAllDay -> {
                val endOfDay = LocalDateTime.of(until.year, until.month + 1, until.dayOfMonth, 23, 59, 59)
                rule.until = toDateTime(event, endOfDay)
            }
            !first.isAllDay && first.isFloating != until.isFloating -> {
                val local = if (until.isFloating) {
                    LocalDateTime.of(until.year, until.month + 1, until.dayOfMonth, until.hours, until.minutes, until.seconds)
                } else {
                    Instant.ofEpochMilli(until.timestamp).atZone(event.zone ?: ZoneId.systemDefault()).toLocalDateTime()
                }
                rule.until = toDateTime(event, local)
            }
        }
    }
}
