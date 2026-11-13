package ch.nthnbch.kalendar

import ch.nthnbch.kalendar.ical.EventDraft
import ch.nthnbch.kalendar.ical.EventParser
import ch.nthnbch.kalendar.ical.EventResource
import ch.nthnbch.kalendar.ical.EventWriter
import ch.nthnbch.kalendar.ical.Ics
import ch.nthnbch.kalendar.ical.Occurrence
import ch.nthnbch.kalendar.ical.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class IcalTest {

    private val zurich = ZoneId.of("Europe/Zurich")

    private fun ics(vararg events: String) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" + events.joinToString("") { "BEGIN:VEVENT\r\n$it\r\nEND:VEVENT\r\n" } + "END:VCALENDAR\r\n"

    private fun expand(text: String, from: String, to: String, zone: ZoneId = zurich): List<Occurrence> {
        val resource = EventResource("/cal/e.ics", "/cal/", null, text, EventParser.parse(text, zone))
        return Recurrence.expand(
            resource,
            LocalDate.parse(from).atStartOfDay(zone).toInstant(),
            LocalDate.parse(to).atStartOfDay(zone).toInstant(),
            zone,
        ).sortedBy { it.start }
    }

    @Test
    fun parsesFoldedEscapedText() {
        val text = ics("UID:1\r\nDTSTART:20261112T090000Z\r\nDTEND:20261112T100000Z\r\nSUMMARY:Déjeuner\\, équipe\r\n  et clients\r\nDESCRIPTION:Ligne 1\\nLigne 2\r\nLOCATION;LANGUAGE=fr:\"Chez\" Léon\\; Lausanne")
        val event = EventParser.parse(text, zurich).single()
        assertEquals("Déjeuner, équipe et clients", event.summary)
        assertEquals("Ligne 1\nLigne 2", event.description)
        assertEquals("\"Chez\" Léon; Lausanne", event.location)
        assertFalse(event.allDay)
    }

    @Test
    fun convertsUtcToDeviceZone() {
        val o = expand(ics("UID:1\r\nDTSTART:20261112T090000Z\r\nDTEND:20261112T100000Z\r\nSUMMARY:a"), "2026-11-12", "2026-11-13").single()
        assertEquals(LocalDateTime.parse("2026-11-12T10:00"), o.start.toLocalDateTime())
        assertEquals(LocalDateTime.parse("2026-11-12T11:00"), o.end.toLocalDateTime())
    }

    @Test
    fun allDayEventSpansItsDaysOnly() {
        val text = ics("UID:1\r\nDTSTART;VALUE=DATE:20261112\r\nDTEND;VALUE=DATE:20261114\r\nSUMMARY:a")
        val o = expand(text, "2026-11-01", "2026-12-01").single()
        assertTrue(o.allDay)
        assertEquals(LocalDate.parse("2026-11-12"), o.firstDay)
        assertEquals(LocalDate.parse("2026-11-13"), o.lastDay)
        assertTrue(expand(text, "2026-11-14", "2026-11-15").isEmpty())
        assertEquals(1, expand(text, "2026-11-13", "2026-11-14").size)
    }

    @Test
    fun weeklyRuleKeepsWallClockAcrossDst() {
        // DST ends on 25 Oct 2026 in Zurich: a 09:00 meeting must stay at 09:00.
        val text = ics("UID:1\r\nDTSTART;TZID=Europe/Zurich:20261019T090000\r\nDTEND;TZID=Europe/Zurich:20261019T100000\r\nRRULE:FREQ=WEEKLY;COUNT=4\r\nSUMMARY:a")
        val all = expand(text, "2026-10-01", "2026-12-01")
        assertEquals(4, all.size)
        assertTrue(all.all { it.start.hour == 9 && it.end.hour == 10 })
        assertEquals(LocalDate.parse("2026-11-09"), all.last().firstDay)
    }

    @Test
    fun exdateAndOverrideReplaceOccurrences() {
        val text = ics(
            "UID:1\r\nDTSTART;TZID=Europe/Zurich:20261102T090000\r\nDTEND;TZID=Europe/Zurich:20261102T100000\r\nRRULE:FREQ=DAILY;COUNT=5\r\nEXDATE;TZID=Europe/Zurich:20261103T090000\r\nSUMMARY:serie",
            "UID:1\r\nRECURRENCE-ID:20261104T080000Z\r\nDTSTART;TZID=Europe/Zurich:20261104T140000\r\nDTEND;TZID=Europe/Zurich:20261104T150000\r\nSUMMARY:deplace",
        )
        val all = expand(text, "2026-11-01", "2026-11-30")
        assertEquals(listOf("serie", "deplace", "serie", "serie"), all.map { it.event.summary })
        assertEquals(listOf(2, 4, 5, 6), all.map { it.start.dayOfMonth })
        assertEquals(14, all[1].start.hour)
    }

    @Test
    fun untilInAnotherFormStillWorks() {
        val allDay = ics("UID:1\r\nDTSTART;VALUE=DATE:20261101\r\nRRULE:FREQ=DAILY;UNTIL=20261103T225959Z\r\nSUMMARY:a")
        assertEquals(3, expand(allDay, "2026-10-01", "2026-12-01").size)
        val timed = ics("UID:1\r\nDTSTART;TZID=Europe/Zurich:20261101T090000\r\nRRULE:FREQ=DAILY;UNTIL=20261103\r\nSUMMARY:a")
        assertEquals(3, expand(timed, "2026-10-01", "2026-12-01").size)
    }

    @Test
    fun oldSeriesIsFastForwarded() {
        val text = ics("UID:1\r\nDTSTART;TZID=Europe/Zurich:20100104T180000\r\nDURATION:PT1H30M\r\nRRULE:FREQ=WEEKLY;BYDAY=MO,TH\r\nSUMMARY:sport")
        val week = expand(text, "2026-11-09", "2026-11-16")
        assertEquals(listOf(9, 12), week.map { it.start.dayOfMonth })
        assertEquals(30, week.first().end.minute)
    }

    @Test
    fun cancelledEventsAreHidden() {
        assertTrue(expand(ics("UID:1\r\nDTSTART:20261112T090000Z\r\nSTATUS:CANCELLED\r\nSUMMARY:a"), "2026-11-01", "2026-12-01").isEmpty())
    }

    @Test
    fun readsAlarmsAndWindowsZones() {
        val text = ics("UID:1\r\nDTSTART;TZID=\"W. Europe Standard Time\":20261112T090000\r\nSUMMARY:a\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT15M\r\nEND:VALARM\r\nBEGIN:VALARM\r\nTRIGGER;RELATED=START:-P1D\r\nEND:VALARM")
        val event = EventParser.parse(text, zurich).single()
        assertEquals(listOf(15, 1440), event.alarms)
        assertEquals(ZoneId.of("Europe/Berlin"), event.zone)
    }

    @Test
    fun createdEventRoundTrips() {
        val draft = EventDraft(
            title = "Café; avec, Léa", location = "Lausanne", description = "a\nb", allDay = false,
            start = LocalDateTime.parse("2026-11-12T09:00"), end = LocalDateTime.parse("2026-11-12T10:30"),
            zone = zurich, rrule = "FREQ=WEEKLY", alarms = listOf(10),
        )
        val text = EventWriter.create(draft, "uid-1")
        assertTrue(text.contains("BEGIN:VTIMEZONE"))
        assertTrue(text.contains("BYDAY=-1SU"))
        val event = EventParser.parse(text, zurich).single()
        assertEquals("Café; avec, Léa", event.summary)
        assertEquals("a\nb", event.description)
        assertEquals(draft.start, event.start)
        assertEquals(draft.end, event.end)
        assertEquals(zurich, event.zone)
        assertEquals("FREQ=WEEKLY", event.rrule)
        assertEquals(listOf(10), event.alarms)
    }

    @Test
    fun updateKeepsUnknownProperties() {
        val original = ics("UID:1\r\nSEQUENCE:2\r\nDTSTART;TZID=Europe/Zurich:20261112T090000\r\nDTEND;TZID=Europe/Zurich:20261112T100000\r\nSUMMARY:old\r\nATTENDEE;CN=Léa;PARTSTAT=ACCEPTED:mailto:lea@example.com\r\nX-CUSTOM:keep")
        val draft = EventDraft("new", allDay = true, start = LocalDateTime.parse("2026-11-13T00:00"), end = LocalDateTime.parse("2026-11-14T00:00"), zone = zurich)
        val updated = EventWriter.update(original, draft)!!
        assertTrue(updated.contains("X-CUSTOM:keep"))
        assertTrue(updated.contains("SEQUENCE:3"))
        val event = EventParser.parse(updated, zurich).single()
        assertEquals("new", event.summary)
        assertTrue(event.allDay)
        assertEquals("Léa", event.attendees.single().name)
        assertEquals(LocalDate.parse("2026-11-13"), event.start.toLocalDate())
    }

    @Test
    fun excludingAnOccurrenceRemovesOnlyThatOne() {
        val text = ics("UID:1\r\nDTSTART;TZID=Europe/Zurich:20261102T090000\r\nRRULE:FREQ=DAILY;COUNT=3\r\nSUMMARY:a")
        val second = expand(text, "2026-11-01", "2026-11-30")[1]
        val updated = EventWriter.excludeOccurrence(text, second.originalStart, zurich)
        assertNotNull(updated)
        assertEquals(listOf(2, 4), expand(updated!!, "2026-11-01", "2026-11-30").map { it.start.dayOfMonth })
    }

    @Test
    fun longLinesAreFoldedAndReadBack() {
        val title = "é".repeat(120)
        val draft = EventDraft(title, allDay = true, start = LocalDateTime.parse("2026-11-13T00:00"), end = LocalDateTime.parse("2026-11-14T00:00"), zone = zurich)
        val text = EventWriter.create(draft, "u")
        assertTrue(text.lines().all { it.toByteArray().size <= 75 })
        assertEquals(title, EventParser.parse(text, zurich).single().summary)
        assertNull(Ics.parse("garbage"))
    }
}
