package ch.nthnbch.kalendar.ical

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class Attendee(val name: String, val email: String, val status: String?)

/**
 * One VEVENT. Every date is a wall-clock time in [zone]; a null zone means "floating"
 * (all-day events, or timed events that follow the device's zone).
 */
data class VEvent(
    val uid: String,
    val summary: String,
    val description: String = "",
    val location: String = "",
    val allDay: Boolean,
    val start: LocalDateTime,
    /** Exclusive. Equal to [start] for zero-length events. */
    val end: LocalDateTime,
    val zone: ZoneId? = null,
    val rrule: String? = null,
    val rdates: List<LocalDateTime> = emptyList(),
    val exdates: Set<LocalDateTime> = emptySet(),
    /** Set on an override of a single occurrence: the original start it replaces, as an occurrence key. */
    val recurrenceKey: Long? = null,
    val cancelled: Boolean = false,
    val color: Int? = null,
    /** Minutes before the start (negative = after). */
    val alarms: List<Int> = emptyList(),
    val attendees: List<Attendee> = emptyList(),
    val organizer: String? = null,
    val organizerEmail: String? = null,
    val url: String? = null,
) {
    val isRecurring: Boolean get() = rrule != null || rdates.isNotEmpty()

    /**
     * Identity of an occurrence across zones: the epoch day for all-day events, the epoch second otherwise.
     * This is what EXDATE and RECURRENCE-ID are matched on.
     */
    fun keyOf(localStart: LocalDateTime, deviceZone: ZoneId): Long =
        if (allDay) localStart.toLocalDate().toEpochDay()
        else localStart.atZone(zone ?: deviceZone).toEpochSecond()
}

/** A calendar object resource as stored on the server: one .ics file holding a master and its overrides. */
data class EventResource(
    val href: String,
    val calendarUrl: String,
    val etag: String?,
    val ics: String,
    val events: List<VEvent>,
)

/** A concrete instance of an event on the timeline, already converted to the device's zone. */
data class Occurrence(
    val event: VEvent,
    val href: String,
    val calendarUrl: String,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    /** Start of this occurrence in the master's own zone, as needed to write an EXDATE. */
    val originalStart: LocalDateTime,
) {
    val allDay: Boolean get() = event.allDay
    val firstDay: LocalDate get() = start.toLocalDate()

    /** Last day the occurrence touches. An end at midnight belongs to the previous day. */
    val lastDay: LocalDate
        get() {
            val endDate = end.toLocalDate()
            val endsAtMidnight = end.toLocalTime().toSecondOfDay() == 0
            return if (end > start && endsAtMidnight) endDate.minusDays(1) else endDate
        }

    val startInstant: Instant get() = start.toInstant()
    val endInstant: Instant get() = end.toInstant()
    fun covers(day: LocalDate): Boolean = !day.isBefore(firstDay) && !day.isAfter(lastDay)
}
