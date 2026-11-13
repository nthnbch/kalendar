package ch.nthnbch.kalendar.ical

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A date as written in an .ics property, before being moved to the event's zone. */
data class IcsDate(val local: LocalDateTime, val zone: ZoneId?, val isDate: Boolean)

object EventParser {

    private val DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /** Returns every VEVENT of the file (master and overrides); malformed events are skipped. */
    fun parse(ics: String, deviceZone: ZoneId = ZoneId.systemDefault()): List<VEvent> {
        val root = Ics.parse(ics) ?: return emptyList()
        val calendar = if (root.name == "VCALENDAR") root else return emptyList()
        return calendar.children("VEVENT").mapNotNull { runCatching { toEvent(it, deviceZone) }.getOrNull() }
    }

    private fun toEvent(c: IcsComponent, deviceZone: ZoneId): VEvent? {
        val dtStart = c.prop("DTSTART")?.let(::parseDate) ?: return null
        val allDay = dtStart.isDate
        val zone = if (allDay) null else dtStart.zone
        val start = dtStart.local

        fun inEventZone(d: IcsDate): LocalDateTime = when {
            allDay -> d.local.toLocalDate().atStartOfDay()
            zone != null && d.zone != null -> d.local.atZone(d.zone).withZoneSameInstant(zone).toLocalDateTime()
            // A floating event compared with an absolute date: read the absolute date on the device's clock.
            zone == null && d.zone != null -> d.local.atZone(d.zone).withZoneSameInstant(deviceZone).toLocalDateTime()
            else -> d.local
        }

        val dtEnd = c.prop("DTEND")?.let(::parseDate)
        val duration = c.prop("DURATION")?.value?.let(::parseDuration)
        val end = when {
            dtEnd != null -> inEventZone(dtEnd)
            duration != null && zone != null -> start.atZone(zone).plus(duration).toLocalDateTime()
            duration != null -> start.plus(duration)
            allDay -> start.plusDays(1)
            else -> start
        }.let { if (it.isBefore(start)) start else it }
            .let { if (allDay && !it.isAfter(start)) start.plusDays(1) else it }

        fun dateList(name: String): List<LocalDateTime> = c.props(name).flatMap { p ->
            p.value.split(',').mapNotNull { v ->
                // RDATE may carry a PERIOD ("start/end"): only its start matters to us.
                parseDate(p.copy(value = v.substringBefore('/').trim()))?.let(::inEventZone)
            }
        }

        val draft = VEvent(
            uid = c.prop("UID")?.value?.trim().orEmpty(),
            summary = Ics.unescape(c.prop("SUMMARY")?.value.orEmpty()).trim(),
            description = Ics.unescape(c.prop("DESCRIPTION")?.value.orEmpty()).trim(),
            location = Ics.unescape(c.prop("LOCATION")?.value.orEmpty()).trim(),
            allDay = allDay,
            start = start,
            end = end,
            zone = zone,
            rrule = c.prop("RRULE")?.value?.trim()?.takeIf { it.isNotEmpty() },
            rdates = dateList("RDATE"),
            exdates = dateList("EXDATE").toSet(),
            cancelled = c.prop("STATUS")?.value?.trim().equals("CANCELLED", ignoreCase = true),
            color = parseColor(c.prop("X-INFOMANIAK-COLOR")?.value) ?: parseColor(c.prop("COLOR")?.value),
            attendees = c.props("ATTENDEE").map { p ->
                val email = stripMailto(p.value)
                Attendee(name = p.param("CN")?.takeIf { it.isNotBlank() } ?: email, email = email, status = p.param("PARTSTAT"))
            },
            organizer = c.prop("ORGANIZER")?.let { p -> p.param("CN")?.takeIf { it.isNotBlank() } ?: stripMailto(p.value) },
            url = c.prop("URL")?.value?.trim()?.takeIf { it.isNotEmpty() },
        )

        val recurrenceKey = c.prop("RECURRENCE-ID")?.let(::parseDate)?.let { draft.keyOf(inEventZone(it), deviceZone) }
        val alarms = c.children("VALARM").mapNotNull { alarmMinutes(it, draft, deviceZone) }.distinct().sorted()
        return draft.copy(recurrenceKey = recurrenceKey, alarms = alarms)
    }

    private fun alarmMinutes(alarm: IcsComponent, event: VEvent, deviceZone: ZoneId): Int? {
        val trigger = alarm.prop("TRIGGER") ?: return null
        val value = trigger.value.trim()
        val relative = parseDuration(value)
        if (relative != null) {
            // RELATED=END is rare: shift by the event's length so it still fires at the right moment.
            val fromStart = if (trigger.param("RELATED").equals("END", ignoreCase = true)) {
                relative.plus(Duration.between(event.start, event.end))
            } else relative
            return (-fromStart.toMinutes()).toInt()
        }
        val absolute = parseDate(trigger) ?: return null
        val eventStart = event.start.atZone(event.zone ?: deviceZone).toInstant()
        val at = absolute.local.atZone(absolute.zone ?: deviceZone).toInstant()
        return Duration.between(at, eventStart).toMinutes().toInt()
    }

    fun parseDate(prop: IcsProp): IcsDate? {
        val raw = prop.value.trim()
        return try {
            when {
                raw.length == 8 -> IcsDate(LocalDate.parse(raw, DATE).atStartOfDay(), null, true)
                raw.endsWith("Z", ignoreCase = true) ->
                    IcsDate(LocalDateTime.parse(raw.dropLast(1), DATE_TIME), ZoneOffset.UTC, false)
                else -> IcsDate(LocalDateTime.parse(raw.take(15), DATE_TIME), prop.param("TZID")?.let(TimeZones::resolve), false)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Parses an RFC 5545 duration such as `-PT15M`, `P1D`, `P1W` or `P1DT2H`. */
    fun parseDuration(text: String): Duration? {
        val m = DURATION.matchEntire(text.trim().uppercase()) ?: return null
        val (sign, weeks, days, hours, minutes, seconds) = m.destructured
        if (weeks.isEmpty() && days.isEmpty() && hours.isEmpty() && minutes.isEmpty() && seconds.isEmpty()) return null
        fun n(s: String) = s.toLongOrNull() ?: 0L
        val total = Duration.ofDays(n(weeks) * 7 + n(days)).plusHours(n(hours)).plusMinutes(n(minutes)).plusSeconds(n(seconds))
        return if (sign == "-") total.negated() else total
    }

    private val DURATION = Regex("""([+-]?)P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""")

    fun parseColor(value: String?): Int? {
        val hex = value?.trim()?.removePrefix("#") ?: return null
        return try {
            when (hex.length) {
                6 -> (0xFF000000 or hex.toLong(16)).toInt()
                // CalDAV colors are #RRGGBBAA; the alpha is dropped, calendars are always drawn opaque.
                8 -> (0xFF000000 or hex.substring(0, 6).toLong(16)).toInt()
                else -> null
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun stripMailto(value: String): String = value.trim().let {
        if (it.startsWith("mailto:", ignoreCase = true)) it.substring(7) else it
    }
}

object TimeZones {
    private val available: Set<String> by lazy { ZoneId.getAvailableZoneIds() }

    /** Names Outlook/Exchange put in invitations instead of IANA identifiers. */
    private val windows = mapOf(
        "W. Europe Standard Time" to "Europe/Berlin",
        "Romance Standard Time" to "Europe/Paris",
        "Central Europe Standard Time" to "Europe/Budapest",
        "Central European Standard Time" to "Europe/Warsaw",
        "GMT Standard Time" to "Europe/London",
        "Greenwich Standard Time" to "Atlantic/Reykjavik",
        "E. Europe Standard Time" to "Europe/Chisinau",
        "FLE Standard Time" to "Europe/Kiev",
        "GTB Standard Time" to "Europe/Bucharest",
        "Turkey Standard Time" to "Europe/Istanbul",
        "Russian Standard Time" to "Europe/Moscow",
        "Israel Standard Time" to "Asia/Jerusalem",
        "Arabian Standard Time" to "Asia/Dubai",
        "India Standard Time" to "Asia/Kolkata",
        "China Standard Time" to "Asia/Shanghai",
        "Singapore Standard Time" to "Asia/Singapore",
        "Tokyo Standard Time" to "Asia/Tokyo",
        "AUS Eastern Standard Time" to "Australia/Sydney",
        "New Zealand Standard Time" to "Pacific/Auckland",
        "Eastern Standard Time" to "America/New_York",
        "Central Standard Time" to "America/Chicago",
        "Mountain Standard Time" to "America/Denver",
        "Pacific Standard Time" to "America/Los_Angeles",
        "Atlantic Standard Time" to "America/Halifax",
        "E. South America Standard Time" to "America/Sao_Paulo",
        "South Africa Standard Time" to "Africa/Johannesburg",
        "UTC" to "UTC",
    )

    /** Never fails: an unknown identifier falls back to the device's zone, which is right far more often than UTC. */
    fun resolve(tzid: String): ZoneId {
        val id = tzid.trim().removeSurrounding("\"")
        if (id in available) return ZoneId.of(id)
        windows[id]?.let { return ZoneId.of(it) }
        // Some producers prefix the IANA name: "/freeassociation.sourceforge.net/Europe/Zurich".
        val parts = id.split('/').filter { it.isNotEmpty() }
        for (n in minOf(3, parts.size) downTo 2) {
            val candidate = parts.takeLast(n).joinToString("/")
            if (candidate in available) return ZoneId.of(candidate)
        }
        return runCatching { ZoneId.of(id) }.getOrElse { ZoneId.systemDefault() }
    }
}
