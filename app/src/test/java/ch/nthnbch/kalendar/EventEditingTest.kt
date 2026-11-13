package ch.nthnbch.kalendar

import ch.nthnbch.kalendar.ical.EventDraft
import ch.nthnbch.kalendar.ical.EventParser
import ch.nthnbch.kalendar.ical.EventResource
import ch.nthnbch.kalendar.ical.EventWriter
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

/** Create / read / update / delete of events and series, entirely on the iCalendar text. */
class EventEditingTest {

    private val zone = ZoneId.of("Europe/Zurich")

    private fun draft(
        title: String = "Réunion",
        start: String = "2026-11-02T09:00",
        end: String = "2026-11-02T10:00",
        rrule: String? = null,
        guests: List<String>? = null,
        alarms: List<Int> = listOf(15),
    ) = EventDraft(
        title = title, allDay = false, start = LocalDateTime.parse(start), end = LocalDateTime.parse(end),
        zone = zone, rrule = rrule, alarms = alarms, guests = guests, organizerEmail = "me@example.org",
    )

    private fun occurrences(ics: String, from: String, to: String): List<Occurrence> {
        val resource = EventResource("/c/e.ics", "/c/", null, ics, EventParser.parse(ics, zone))
        return Recurrence.expand(
            resource, LocalDate.parse(from).atStartOfDay(zone).toInstant(), LocalDate.parse(to).atStartOfDay(zone).toInstant(), zone,
        ).sortedBy { it.start }
    }

    private fun starts(ics: String, from: String = "2026-11-01", to: String = "2027-01-01") =
        occurrences(ics, from, to).map { it.start.toLocalDateTime().toString() }

    // ---- create / read -------------------------------------------------------------

    @Test
    fun createdEventReadsBackIdentically() {
        val ics = EventWriter.create(draft(title = "Dîner, chez Léon; 20h"), "uid-1")
        val event = EventParser.parse(ics, zone).single()
        assertEquals("uid-1", event.uid)
        assertEquals("Dîner, chez Léon; 20h", event.summary)
        assertEquals(LocalDateTime.parse("2026-11-02T09:00"), event.start)
        assertEquals(LocalDateTime.parse("2026-11-02T10:00"), event.end)
        assertEquals(zone, event.zone)
        assertEquals(listOf(15), event.alarms)
        assertTrue("a TZID needs its VTIMEZONE", ics.contains("BEGIN:VTIMEZONE"))
    }

    @Test
    fun allDayEventUsesExclusiveDateEnd() {
        val d = draft().copy(allDay = true, start = LocalDateTime.parse("2026-11-02T00:00"), end = LocalDateTime.parse("2026-11-04T00:00"))
        val event = EventParser.parse(EventWriter.create(d, "u"), zone).single()
        assertTrue(event.allDay)
        assertEquals(LocalDateTime.parse("2026-11-04T00:00"), event.end)
    }

    // ---- update --------------------------------------------------------------------

    @Test
    fun updateKeepsUnknownPropertiesAndBumpsSequence() {
        val original = EventWriter.create(draft(), "u").replace("SUMMARY:", "X-CUSTOM:keep-me\r\nSUMMARY:")
        val updated = EventWriter.update(original, draft(title = "Renommée", start = "2026-11-02T11:00", end = "2026-11-02T12:00"))!!
        assertTrue(updated.contains("X-CUSTOM:keep-me"))
        assertTrue(updated.contains("SEQUENCE:1"))
        val event = EventParser.parse(updated, zone).single()
        assertEquals("Renommée", event.summary)
        assertEquals(LocalDateTime.parse("2026-11-02T11:00"), event.start)
    }

    @Test
    fun updateCanAddAndRemoveReminders() {
        val original = EventWriter.create(draft(alarms = listOf(10, 60)), "u")
        assertEquals(listOf(10, 60), EventParser.parse(original, zone).single().alarms)
        val none = EventWriter.update(original, draft(alarms = emptyList()))!!
        assertTrue(EventParser.parse(none, zone).single().alarms.isEmpty())
    }

    // ---- delete --------------------------------------------------------------------

    @Test
    fun excludingOneOccurrenceHidesOnlyThatOne() {
        val ics = EventWriter.create(draft(rrule = "FREQ=WEEKLY;COUNT=4"), "u")
        val slot = LocalDateTime.parse("2026-11-09T09:00")
        val without = EventWriter.excludeOccurrence(ics, slot, zone)!!
        assertEquals(listOf("2026-11-02T09:00", "2026-11-16T09:00", "2026-11-23T09:00"), starts(without))
    }

    @Test
    fun truncatingEndsTheSeriesBeforeTheSlot() {
        val ics = EventWriter.create(draft(rrule = "FREQ=WEEKLY;COUNT=6"), "u")
        val cut = EventWriter.truncateSeries(ics, LocalDateTime.parse("2026-11-16T09:00"), zone)!!
        assertEquals(listOf("2026-11-02T09:00", "2026-11-09T09:00"), starts(cut))
        assertFalse(cut.contains("COUNT="))
    }

    @Test
    fun truncatingAtTheFirstOccurrenceIsRefused() {
        val ics = EventWriter.create(draft(rrule = "FREQ=WEEKLY"), "u")
        assertNull(EventWriter.truncateSeries(ics, LocalDateTime.parse("2026-11-02T09:00"), zone))
    }

    // ---- editing a single occurrence ---------------------------------------------------

    @Test
    fun editingOneOccurrenceLeavesTheRestAlone() {
        val ics = EventWriter.create(draft(rrule = "FREQ=WEEKLY;COUNT=3"), "u")
        val slot = LocalDateTime.parse("2026-11-09T09:00")
        val edited = EventWriter.updateOccurrence(
            ics, draft(title = "Déplacée", start = "2026-11-09T14:00", end = "2026-11-09T15:00", rrule = "FREQ=WEEKLY;COUNT=3"), slot, zone,
        )!!
        val found = occurrences(edited, "2026-11-01", "2026-12-01")
        assertEquals(listOf("2026-11-02T09:00", "2026-11-09T14:00", "2026-11-16T09:00"), found.map { it.start.toLocalDateTime().toString() })
        assertEquals(listOf("Réunion", "Déplacée", "Réunion"), found.map { it.event.summary })
        // The override must not become a series of its own.
        assertEquals(1, EventParser.parse(edited, zone).count { it.rrule != null })
        assertTrue(edited.contains("RECURRENCE-ID;TZID=Europe/Zurich:20261109T090000"))
    }

    @Test
    fun editingTheSameOccurrenceTwiceReusesItsOverride() {
        val ics = EventWriter.create(draft(rrule = "FREQ=WEEKLY;COUNT=3"), "u")
        val slot = LocalDateTime.parse("2026-11-09T09:00")
        val once = EventWriter.updateOccurrence(ics, draft(title = "A", start = "2026-11-09T10:00", end = "2026-11-09T11:00"), slot, zone)!!
        val twice = EventWriter.updateOccurrence(once, draft(title = "B", start = "2026-11-09T10:00", end = "2026-11-09T11:00"), slot, zone)!!
        val events = EventParser.parse(twice, zone)
        assertEquals(2, events.size)
        assertEquals("B", events.single { it.recurrenceKey != null }.summary)
    }

    @Test
    fun deletingAnEditedOccurrenceAlsoDropsItsOverride() {
        val ics = EventWriter.create(draft(rrule = "FREQ=WEEKLY;COUNT=3"), "u")
        val slot = LocalDateTime.parse("2026-11-09T09:00")
        val edited = EventWriter.updateOccurrence(ics, draft(title = "X", start = "2026-11-09T10:00", end = "2026-11-09T11:00"), slot, zone)!!
        val gone = EventWriter.excludeOccurrence(edited, slot, zone)!!
        assertEquals(0, EventParser.parse(gone, zone).count { it.recurrenceKey != null })
        assertEquals(listOf("2026-11-02T09:00", "2026-11-16T09:00"), starts(gone))
    }

    // ---- this and following ----------------------------------------------------------

    @Test
    fun splittingMovesTheTailToANewSeries() {
        val ics = EventWriter.create(draft(rrule = "FREQ=WEEKLY;COUNT=5"), "old")
        val slot = LocalDateTime.parse("2026-11-16T09:00")
        val split = EventWriter.splitSeries(
            ics, draft(title = "Nouvelle", start = "2026-11-16T18:00", end = "2026-11-16T19:00", rrule = "FREQ=WEEKLY;COUNT=5"),
            slot, zone, "new",
        )!!
        assertEquals(listOf("2026-11-02T09:00", "2026-11-09T09:00"), starts(split.truncated))
        // 5 occurrences in all, 2 stay with the old series: the new one has 3 left.
        assertEquals(listOf("2026-11-16T18:00", "2026-11-23T18:00", "2026-11-30T18:00"), starts(split.created))
        val created = EventParser.parse(split.created, zone).single()
        assertEquals("new", created.uid)
        assertEquals("Nouvelle", created.summary)
        assertEquals("old", EventParser.parse(split.truncated, zone).single().uid)
    }

    @Test
    fun splittingDropsOverridesAfterTheSplitOnly() {
        val ics = EventWriter.create(draft(rrule = "FREQ=WEEKLY;COUNT=5"), "u")
        val early = EventWriter.updateOccurrence(ics, draft(title = "Early", start = "2026-11-09T10:00", end = "2026-11-09T11:00"), LocalDateTime.parse("2026-11-09T09:00"), zone)!!
        val both = EventWriter.updateOccurrence(early, draft(title = "Late", start = "2026-11-23T10:00", end = "2026-11-23T11:00"), LocalDateTime.parse("2026-11-23T09:00"), zone)!!
        val split = EventWriter.splitSeries(both, draft(rrule = "FREQ=WEEKLY;COUNT=5"), LocalDateTime.parse("2026-11-16T09:00"), zone, "n")!!
        val kept = EventParser.parse(split.truncated, zone).filter { it.recurrenceKey != null }
        assertEquals(listOf("Early"), kept.map { it.summary })
    }

    @Test
    fun splittingAllDaySeriesEndsOnTheDayBefore() {
        val allDay = draft(rrule = "FREQ=DAILY;COUNT=5").copy(allDay = true, start = LocalDateTime.parse("2026-11-02T00:00"), end = LocalDateTime.parse("2026-11-03T00:00"))
        val ics = EventWriter.create(allDay, "u")
        val split = EventWriter.splitSeries(ics, allDay.copy(title = "N", rrule = "FREQ=DAILY;COUNT=5"), LocalDateTime.parse("2026-11-04T00:00"), zone, "n")!!
        assertTrue(split.truncated.contains("UNTIL=20261103"))
        assertEquals(2, occurrences(split.truncated, "2026-11-01", "2026-12-01").size)
    }

    @Test
    fun splittingAtTheFirstOccurrenceIsRefused() {
        val ics = EventWriter.create(draft(rrule = "FREQ=WEEKLY"), "u")
        assertNull(EventWriter.splitSeries(ics, draft(), LocalDateTime.parse("2026-11-02T09:00"), zone, "n"))
    }

    @Test
    fun countBeforeCountsOccurrencesStrictlyBefore() {
        val event = EventParser.parse(EventWriter.create(draft(rrule = "FREQ=DAILY"), "u"), zone).single()
        assertEquals(0, Recurrence.countBefore(event, LocalDateTime.parse("2026-11-02T09:00")))
        assertEquals(3, Recurrence.countBefore(event, LocalDateTime.parse("2026-11-05T09:00")))
    }

    // ---- invitations ---------------------------------------------------------------

    @Test
    fun guestsGetAnInvitationAndTheOrganizerIsSet() {
        val ics = EventWriter.create(draft(guests = listOf("ana@example.org", "bob@example.org")), "u")
        val event = EventParser.parse(ics, zone).single()
        assertEquals(listOf("ana@example.org", "bob@example.org"), event.attendees.map { it.email })
        assertTrue(event.attendees.all { it.status == "NEEDS-ACTION" })
        assertEquals("me@example.org", event.organizerEmail)
    }

    @Test
    fun updatingGuestsKeepsExistingRepliesAndRemovesDroppedGuests() {
        val ics = EventWriter.create(draft(guests = listOf("ana@example.org", "bob@example.org")), "u")
        val answered = EventWriter.respond(ics, "ana@example.org", "ACCEPTED")!!
        val updated = EventWriter.update(answered, draft(guests = listOf("ana@example.org", "cy@example.org")))!!
        val attendees = EventParser.parse(updated, zone).single().attendees
        assertEquals(listOf("ana@example.org", "cy@example.org"), attendees.map { it.email })
        assertEquals("ACCEPTED", attendees.first().status)
        assertEquals("NEEDS-ACTION", attendees.last().status)
    }

    @Test
    fun omittingGuestsLeavesInvitationsUntouched() {
        val ics = EventWriter.create(draft(guests = listOf("ana@example.org")), "u")
        val updated = EventWriter.update(ics, draft(title = "Autre", guests = null))!!
        assertEquals(1, EventParser.parse(updated, zone).single().attendees.size)
    }

    @Test
    fun respondingChangesOnlyMyPartstat() {
        val ics = EventWriter.create(draft(guests = listOf("me@example.org", "ana@example.org")), "u")
        val replied = EventWriter.respond(ics, "ME@Example.org", "DECLINED")!!
        val attendees = EventParser.parse(replied, zone).single().attendees.associate { it.email to it.status }
        assertEquals("DECLINED", attendees["me@example.org"])
        assertEquals("NEEDS-ACTION", attendees["ana@example.org"])
        assertFalse("RSVP is cleared once answered", replied.lines().any { it.contains("me@example.org") && it.contains("RSVP") })
    }

    @Test
    fun respondingWhenNotInvitedDoesNothing() {
        val ics = EventWriter.create(draft(guests = listOf("ana@example.org")), "u")
        assertNull(EventWriter.respond(ics, "stranger@example.org", "ACCEPTED"))
    }

    @Test
    fun respondingAppliesToOverridesToo() {
        val ics = EventWriter.create(draft(rrule = "FREQ=WEEKLY;COUNT=3", guests = listOf("me@example.org")), "u")
        val edited = EventWriter.updateOccurrence(ics, draft(title = "X", start = "2026-11-09T10:00", end = "2026-11-09T11:00"), LocalDateTime.parse("2026-11-09T09:00"), zone)!!
        val replied = EventWriter.respond(edited, "me@example.org", "TENTATIVE")!!
        assertTrue(EventParser.parse(replied, zone).all { e -> e.attendees.all { it.status == "TENTATIVE" } })
        assertNotNull(replied)
    }
}
