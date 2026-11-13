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
    /** Guest e-mail addresses. Null leaves the invitations of an existing event untouched. */
    val guests: List<String>? = null,
    /** The account's own address, used as ORGANIZER when guests are added to an event that has none. */
    val organizerEmail: String? = null,
)

/** Which part of a recurring series an edit or a deletion applies to. */
enum class Scope { THIS, FOLLOWING, ALL }

/** Result of splitting a series: the old file cut short, and the file for the part that starts at the split. */
data class SeriesSplit(val truncated: String, val created: String)

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

    /** A date property (EXDATE, RECURRENCE-ID…) naming [slot] in the same form as the master's DTSTART. */
    private fun slotProp(name: String, dtStart: IcsProp, slot: LocalDateTime): IcsProp {
        val parsed = EventParser.parseDate(dtStart)
        return when {
            parsed?.isDate == true -> IcsProp(name, listOf("VALUE" to "DATE"), slot.toLocalDate().format(DATE))
            dtStart.value.trim().endsWith("Z", ignoreCase = true) -> IcsProp(name, value = slot.format(DATE_TIME) + "Z")
            else -> IcsProp(name, dtStart.params.filter { it.first == "TZID" }, slot.format(DATE_TIME))
        }
    }

    private fun masterOf(calendar: IcsComponent): IcsComponent? =
        calendar.children("VEVENT").firstOrNull { it.prop("RECURRENCE-ID") == null }

    /** The override standing for the occurrence with [key], if any. */
    private fun overrideFor(calendar: IcsComponent, master: VEvent, key: Long, deviceZone: ZoneId): IcsComponent? =
        calendar.children("VEVENT").firstOrNull { child ->
            child.prop("RECURRENCE-ID")?.let(EventParser::parseDate)?.let { d ->
                master.keyOf(EventParser.localIn(d, master.allDay, master.zone, deviceZone), deviceZone) == key
            } == true
        }

    /** Removes one occurrence of a series by excluding its start, and drops its override if it had one. */
    fun excludeOccurrence(ics: String, originalStart: LocalDateTime, deviceZone: ZoneId, now: Instant = Instant.now()): String? {
        val calendar = Ics.parse(ics)?.takeIf { it.name == "VCALENDAR" } ?: return null
        val master = masterOf(calendar) ?: return null
        val dtStart = master.prop("DTSTART") ?: return null
        master.props += slotProp("EXDATE", dtStart, originalStart)

        val masterEvent = EventParser.parse(ics, deviceZone).firstOrNull { it.recurrenceKey == null }
        if (masterEvent != null) {
            overrideFor(calendar, masterEvent, masterEvent.keyOf(originalStart, deviceZone), deviceZone)
                ?.let { calendar.children.remove(it) }
        }
        master.set(IcsProp("LAST-MODIFIED", value = utc(now)))
        return Ics.serialize(calendar)
    }

    /** Changes a single occurrence of a series by writing (or rewriting) an override that carries RECURRENCE-ID. */
    fun updateOccurrence(
        ics: String, draft: EventDraft, slot: LocalDateTime, deviceZone: ZoneId, now: Instant = Instant.now(),
    ): String? {
        val calendar = Ics.parse(ics)?.takeIf { it.name == "VCALENDAR" } ?: return null
        val master = masterOf(calendar) ?: return null
        val masterEvent = EventParser.parse(ics, deviceZone).firstOrNull { it.recurrenceKey == null } ?: return null
        val dtStart = master.prop("DTSTART") ?: return null

        val existing = overrideFor(calendar, masterEvent, masterEvent.keyOf(slot, deviceZone), deviceZone)
        val target = existing ?: master.deepCopy().also { copy ->
            listOf("RRULE", "RDATE", "EXDATE", "SEQUENCE", "CREATED").forEach(copy::remove)
            copy.set(slotProp("RECURRENCE-ID", dtStart, slot))
            calendar.children.add(calendar.children.indexOf(master) + 1, copy)
        }
        // The draft's guests are applied to the series, not to a single occurrence.
        apply(calendar, target, draft.copy(rrule = null, guests = null), now)
        bump(target)
        return Ics.serialize(calendar)
    }

    /**
     * "This and the following": the series ends just before [slot], and a new series (new UID) carries the edit from there.
     * Null when [slot] is the first occurrence (that is simply an edit of the whole series).
     */
    fun splitSeries(
        ics: String, draft: EventDraft, slot: LocalDateTime, deviceZone: ZoneId, newUid: String, now: Instant = Instant.now(),
    ): SeriesSplit? {
        val calendar = Ics.parse(ics)?.takeIf { it.name == "VCALENDAR" } ?: return null
        val master = masterOf(calendar) ?: return null
        val masterEvent = EventParser.parse(ics, deviceZone).firstOrNull { it.recurrenceKey == null } ?: return null
        val dtStart = master.prop("DTSTART") ?: return null
        val rrule = masterEvent.rrule ?: return null
        val slotKey = masterEvent.keyOf(slot, deviceZone)
        if (slotKey <= masterEvent.keyOf(masterEvent.start, deviceZone)) return null

        // The copy for the new series is taken before the old one is cut.
        val fresh = calendar.deepCopy()
        val before = Recurrence.countBefore(masterEvent, slot)

        cutBefore(calendar, master, masterEvent, dtStart, rrule, slot, slotKey, deviceZone)
        bump(master)

        // New series: same event (guests, extensions…) with the edit applied, starting at the split.
        val newMaster = masterOf(fresh) ?: return null
        fresh.children.removeAll { it.name == "VEVENT" && it !== newMaster }
        listOf("RECURRENCE-ID", "EXDATE", "RDATE", "SEQUENCE").forEach(newMaster::remove)
        newMaster.set(IcsProp("UID", value = newUid))
        val countMatches = Regex("COUNT=(\\d+)").find(draft.rrule.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        val adjusted = draft.rrule?.let { r ->
            if (countMatches != null && countMatches == Regex("COUNT=(\\d+)").find(rrule)?.groupValues?.get(1)?.toIntOrNull()) {
                r.replace(Regex("COUNT=\\d+"), "COUNT=${(countMatches - before).coerceAtLeast(1)}")
            } else r
        }
        apply(fresh, newMaster, draft.copy(rrule = adjusted), now)
        newMaster.set(IcsProp("CREATED", value = utc(now)))
        return SeriesSplit(Ics.serialize(calendar), Ics.serialize(fresh))
    }

    /** Ends the series just before [slot] ("delete this and the following"). Null if [slot] is the first occurrence. */
    fun truncateSeries(ics: String, slot: LocalDateTime, deviceZone: ZoneId): String? {
        val calendar = Ics.parse(ics)?.takeIf { it.name == "VCALENDAR" } ?: return null
        val master = masterOf(calendar) ?: return null
        val masterEvent = EventParser.parse(ics, deviceZone).firstOrNull { it.recurrenceKey == null } ?: return null
        val dtStart = master.prop("DTSTART") ?: return null
        val rrule = masterEvent.rrule ?: return null
        val slotKey = masterEvent.keyOf(slot, deviceZone)
        if (slotKey <= masterEvent.keyOf(masterEvent.start, deviceZone)) return null
        cutBefore(calendar, master, masterEvent, dtStart, rrule, slot, slotKey, deviceZone)
        bump(master)
        return Ics.serialize(calendar)
    }

    private fun cutBefore(
        calendar: IcsComponent, master: IcsComponent, masterEvent: VEvent, dtStart: IcsProp, rrule: String,
        slot: LocalDateTime, slotKey: Long, deviceZone: ZoneId,
    ) {
        // Old series: stop one second (one day for all-day events) before the split.
        val untilProp = when {
            masterEvent.allDay -> slot.toLocalDate().minusDays(1).format(DATE)
            masterEvent.zone != null || dtStart.value.trim().endsWith("Z", ignoreCase = true) ->
                utc(slot.atZone(masterEvent.zone ?: deviceZone).toInstant().minusSeconds(1))
            else -> slot.minusSeconds(1).format(DATE_TIME)
        }
        master.set(IcsProp("RRULE", value = withUntil(rrule, untilProp)))
        master.props.removeAll { p -> p.name == "EXDATE" && exdateKeys(p, masterEvent, deviceZone).any { it >= slotKey } }
        calendar.children.removeAll { child ->
            child !== master && child.name == "VEVENT" && child.prop("RECURRENCE-ID")?.let(EventParser::parseDate)?.let { d ->
                masterEvent.keyOf(EventParser.localIn(d, masterEvent.allDay, masterEvent.zone, deviceZone), deviceZone) >= slotKey
            } == true
        }
    }

    private fun exdateKeys(p: IcsProp, master: VEvent, deviceZone: ZoneId): List<Long> =
        p.value.split(',').mapNotNull { v ->
            EventParser.parseDate(p.copy(value = v.trim()))?.let {
                master.keyOf(EventParser.localIn(it, master.allDay, master.zone, deviceZone), deviceZone)
            }
        }

    private fun withUntil(rrule: String, until: String): String =
        rrule.split(';').filter { it.isNotBlank() && !it.startsWith("COUNT=", true) && !it.startsWith("UNTIL=", true) }
            .plus("UNTIL=$until").joinToString(";")

    /**
     * Answers an invitation: sets PARTSTAT for the attendee whose address is [email] (on every VEVENT of the file).
     * The server forwards the reply to the organizer. Null when [email] is not among the attendees.
     */
    fun respond(ics: String, email: String, status: String, now: Instant = Instant.now()): String? {
        val calendar = Ics.parse(ics)?.takeIf { it.name == "VCALENDAR" } ?: return null
        var found = false
        for (event in calendar.children("VEVENT")) {
            val mine = event.props.indices.filter { i ->
                val p = event.props[i]
                p.name == "ATTENDEE" && EventParser.stripMailto(p.value).equals(email.trim(), ignoreCase = true)
            }
            for (i in mine) {
                val p = event.props[i]
                event.props[i] = p.copy(params = p.params.filter { it.first != "PARTSTAT" && it.first != "RSVP" } + ("PARTSTAT" to status))
                found = true
            }
            if (mine.isNotEmpty()) event.set(IcsProp("DTSTAMP", value = utc(now)))
        }
        return if (found) Ics.serialize(calendar) else null
    }

    private fun bump(event: IcsComponent) {
        val sequence = event.prop("SEQUENCE")?.value?.trim()?.toIntOrNull() ?: 0
        event.set(IcsProp("SEQUENCE", value = (sequence + 1).toString()))
    }

    /** Aligns the ATTENDEE list with [guests]; people already invited keep their reply. */
    private fun applyGuests(event: IcsComponent, guests: List<String>, organizerEmail: String?) {
        val wanted = guests.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        event.props.removeAll { p ->
            p.name == "ATTENDEE" && wanted.none { it.equals(EventParser.stripMailto(p.value), ignoreCase = true) }
        }
        val known = event.props("ATTENDEE").map { EventParser.stripMailto(it.value).lowercase() }.toSet()
        for (guest in wanted.filter { it.lowercase() !in known }) {
            event.props += IcsProp("ATTENDEE", listOf("PARTSTAT" to "NEEDS-ACTION", "RSVP" to "TRUE"), "mailto:$guest")
        }
        if (wanted.isNotEmpty() && event.prop("ORGANIZER") == null && !organizerEmail.isNullOrBlank()) {
            event.set(IcsProp("ORGANIZER", value = "mailto:${organizerEmail.trim()}"))
        }
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

        draft.guests?.let { applyGuests(event, it, draft.organizerEmail) }

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
