package ch.nthnbch.kalendar.ical

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.zone.ZoneOffsetTransitionRule
import java.util.UUID

/** What the editor screen produces. For all-day events [end] is the exclusive end (start of the day after the last). */
data class EventDraft(
    val title: String,
    val location: String = "",
    val description: String = "",
    val allDay: Boolean,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val zone: ZoneId,
    val rrule: String? = null,
    val alarms: List<Int> = emptyList(),
)

object EventWriter {

    private const val PRODID = "-//nthnbch//Kalendar//EN"
    private val DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    fun newUid(): String = UUID.randomUUID().toString()

    fun create(draft: EventDraft, uid: String, now: Instant = Instant.now()): String {
        val calendar = IcsComponent("VCALENDAR")
        calendar.props += IcsProp("VERSION", value = "2.0")
        calendar.props += IcsProp("PRODID", value = PRODID)
        val event = IcsComponent("VEVENT")
        event.props += IcsProp("UID", value = uid)
        event.props += IcsProp("CREATED", value = utc(now))
        calendar.children += event
        apply(calendar, event, draft, now)
        return Ics.serialize(calendar)
    }

    /**
     * Rewrites the master event of an existing file, leaving every property this app does not edit
     * (attendees, organizer, attachments, vendor extensions…) untouched.
     */
    fun update(ics: String, draft: EventDraft, now: Instant = Instant.now()): String? {
        val calendar = Ics.parse(ics)?.takeIf { it.name == "VCALENDAR" } ?: return null
        val events = calendar.children("VEVENT")
        val master = events.firstOrNull { it.prop("RECURRENCE-ID") == null } ?: events.firstOrNull() ?: return null

        val before = master.prop("DTSTART")?.value
        apply(calendar, master, draft, now)
        if (master.prop("DTSTART")?.value != before || draft.rrule == null) {
            // Exceptions are anchored on the old occurrence times: once the series moves they point at nothing.
            calendar.children.removeAll { it.name == "VEVENT" && it !== master }
            master.remove("EXDATE")
            master.remove("RDATE")
        }
        val sequence = master.prop("SEQUENCE")?.value?.trim()?.toIntOrNull() ?: 0
        master.set(IcsProp("SEQUENCE", value = (sequence + 1).toString()))
        return Ics.serialize(calendar)
    }

    /** Removes one occurrence of a series by excluding its start, and drops its override if it had one. */
    fun excludeOccurrence(ics: String, originalStart: LocalDateTime, deviceZone: ZoneId, now: Instant = Instant.now()): String? {
        val calendar = Ics.parse(ics)?.takeIf { it.name == "VCALENDAR" } ?: return null
        val master = calendar.children("VEVENT").firstOrNull { it.prop("RECURRENCE-ID") == null } ?: return null
        val dtStart = master.prop("DTSTART") ?: return null
        val parsedStart = EventParser.parseDate(dtStart) ?: return null

        val exdate = when {
            parsedStart.isDate -> IcsProp("EXDATE", listOf("VALUE" to "DATE"), originalStart.toLocalDate().format(DATE))
            dtStart.value.trim().endsWith("Z", ignoreCase = true) -> IcsProp("EXDATE", value = originalStart.format(DATE_TIME) + "Z")
            else -> IcsProp("EXDATE", dtStart.params.filter { it.first == "TZID" }, originalStart.format(DATE_TIME))
        }
        master.props += exdate

        val parsed = EventParser.parse(ics, deviceZone)
        val masterEvent = parsed.firstOrNull { it.recurrenceKey == null }
        if (masterEvent != null) {
            val key = masterEvent.keyOf(originalStart, deviceZone)
            val uid = master.prop("UID")?.value
            calendar.children.removeAll { child ->
                child.name == "VEVENT" && child !== master && child.prop("UID")?.value == uid &&
                    child.prop("RECURRENCE-ID")?.let(EventParser::parseDate)?.let { d ->
                        val local = if (masterEvent.zone != null && d.zone != null) {
                            d.local.atZone(d.zone).withZoneSameInstant(masterEvent.zone).toLocalDateTime()
                        } else d.local
                        masterEvent.keyOf(local, deviceZone) == key
                    } == true
            }
        }
        master.set(IcsProp("LAST-MODIFIED", value = utc(now)))
        return Ics.serialize(calendar)
    }

    private fun apply(calendar: IcsComponent, event: IcsComponent, draft: EventDraft, now: Instant) {
        event.set(IcsProp("DTSTAMP", value = utc(now)))
        event.set(IcsProp("LAST-MODIFIED", value = utc(now)))
        event.set(IcsProp("SUMMARY", value = Ics.escape(draft.title)))
        setOrRemove(event, "LOCATION", draft.location)
        setOrRemove(event, "DESCRIPTION", draft.description)

        val previousStart = event.prop("DTSTART")
        event.remove("DURATION")
        if (draft.allDay) {
            val start = draft.start.toLocalDate()
            val end = draft.end.toLocalDate().let { if (it.isAfter(start)) it else start.plusDays(1) }
            event.set(IcsProp("DTSTART", listOf("VALUE" to "DATE"), start.format(DATE)))
            event.set(IcsProp("DTEND", listOf("VALUE" to "DATE"), end.format(DATE)))
        } else {
            val end = if (draft.end.isBefore(draft.start)) draft.start else draft.end
            if (draft.zone == ZoneOffset.UTC || draft.zone.id == "UTC" || draft.zone.id == "Z") {
                event.set(IcsProp("DTSTART", value = draft.start.format(DATE_TIME) + "Z"))
                event.set(IcsProp("DTEND", value = end.format(DATE_TIME) + "Z"))
            } else {
                // Reuse the file's own TZID when it names the same zone, so its VTIMEZONE keeps matching.
                val existing = previousStart?.param("TZID")?.takeIf { TimeZones.resolve(it) == draft.zone }
                val tzid = existing ?: draft.zone.id
                event.set(IcsProp("DTSTART", listOf("TZID" to tzid), draft.start.format(DATE_TIME)))
                event.set(IcsProp("DTEND", listOf("TZID" to tzid), end.format(DATE_TIME)))
                val declared = calendar.children("VTIMEZONE").any { it.prop("TZID")?.value?.trim() == tzid }
                if (!declared) calendar.children.add(0, vTimeZone(draft.zone, tzid))
            }
        }

        if (draft.rrule.isNullOrBlank()) event.remove("RRULE") else event.set(IcsProp("RRULE", value = draft.rrule))

        event.children.removeAll { it.name == "VALARM" }
        for (minutes in draft.alarms.distinct()) {
            val alarm = IcsComponent("VALARM")
            alarm.props += IcsProp("ACTION", value = "DISPLAY")
            alarm.props += IcsProp("DESCRIPTION", value = Ics.escape(draft.title))
            alarm.props += IcsProp("TRIGGER", value = trigger(minutes))
            event.children += alarm
        }
    }

    private fun setOrRemove(event: IcsComponent, name: String, text: String) {
        if (text.isBlank()) event.remove(name) else event.set(IcsProp(name, value = Ics.escape(text.trim())))
    }

    fun trigger(minutesBefore: Int): String {
        if (minutesBefore == 0) return "PT0S"
        val sign = if (minutesBefore > 0) "-" else ""
        val m = kotlin.math.abs(minutesBefore)
        return when {
            m % (7 * 1440) == 0 -> "${sign}P${m / (7 * 1440)}W"
            m % 1440 == 0 -> "${sign}P${m / 1440}D"
            m % 60 == 0 -> "${sign}PT${m / 60}H"
            else -> "${sign}PT${m}M"
        }
    }

    private fun utc(instant: Instant): String = instant.atZone(ZoneOffset.UTC).toLocalDateTime().format(DATE_TIME) + "Z"

    /** A VTIMEZONE describing the zone's current rules, which is what RFC 5545 requires next to a TZID. */
    private fun vTimeZone(zone: ZoneId, tzid: String): IcsComponent {
        val tz = IcsComponent("VTIMEZONE")
        tz.props += IcsProp("TZID", value = tzid)
        val rules = zone.rules
        val transitions = rules.transitionRules
        if (transitions.isEmpty()) {
            val offset = rules.getOffset(Instant.now())
            tz.children += IcsComponent("STANDARD").apply {
                props += IcsProp("DTSTART", value = "19700101T000000")
                props += IcsProp("TZOFFSETFROM", value = offset(offset))
                props += IcsProp("TZOFFSETTO", value = offset(offset))
            }
            return tz
        }
        for (rule in transitions) {
            val daylight = rule.offsetAfter.totalSeconds > rule.offsetBefore.totalSeconds
            tz.children += IcsComponent(if (daylight) "DAYLIGHT" else "STANDARD").apply {
                val first = rule.createTransition(1970).dateTimeBefore
                props += IcsProp("DTSTART", value = first.format(DATE_TIME))
                props += IcsProp("TZOFFSETFROM", value = offset(rule.offsetBefore))
                props += IcsProp("TZOFFSETTO", value = offset(rule.offsetAfter))
                props += IcsProp("RRULE", value = yearly(rule))
            }
        }
        return tz
    }

    private fun yearly(rule: ZoneOffsetTransitionRule): String {
        val month = rule.month.value
        val dow: DayOfWeek? = rule.dayOfWeek
        val indicator = rule.dayOfMonthIndicator
        return when {
            dow == null -> "FREQ=YEARLY;BYMONTH=$month;BYMONTHDAY=$indicator"
            // "Last Sunday" reaches java.time either as a negative indicator or as "Sunday on or after the 25th".
            indicator < 0 || indicator + 7 > rule.month.maxLength() -> "FREQ=YEARLY;BYMONTH=$month;BYDAY=-1${day(dow)}"
            else -> "FREQ=YEARLY;BYMONTH=$month;BYDAY=${(indicator - 1) / 7 + 1}${day(dow)}"
        }
    }

    private fun day(d: DayOfWeek): String = d.name.take(2)

    private fun offset(o: ZoneOffset): String {
        val total = o.totalSeconds
        val abs = kotlin.math.abs(total)
        return "%s%02d%02d".format(if (total < 0) "-" else "+", abs / 3600, (abs % 3600) / 60)
    }
}
