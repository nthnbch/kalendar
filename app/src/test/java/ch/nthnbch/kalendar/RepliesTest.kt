package ch.nthnbch.kalendar

import ch.nthnbch.kalendar.data.Replies
import ch.nthnbch.kalendar.ical.EventDraft
import ch.nthnbch.kalendar.ical.EventWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class RepliesTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private val me = "me@example.org"

    private fun invitation() = EventWriter.create(
        EventDraft(
            title = "Dîner", allDay = false, start = LocalDateTime.parse("2026-11-20T19:00"),
            end = LocalDateTime.parse("2026-11-20T20:00"), zone = zone,
            guests = listOf("ana@example.org", "bob@example.org"), organizerEmail = me,
        ),
        "u1",
    )

    @Test
    fun aGuestAnsweringIsReported() {
        val before = invitation()
        val after = EventWriter.respond(before, "ana@example.org", "ACCEPTED")!!
        val replies = Replies.detect("/c/e.ics", before, after, me, zone)
        assertEquals(1, replies.size)
        assertEquals("ACCEPTED", replies.single().status)
        assertEquals("Dîner", replies.single().title)
    }

    @Test
    fun anUnchangedEventReportsNothing() {
        val before = EventWriter.respond(invitation(), "ana@example.org", "ACCEPTED")!!
        assertTrue(Replies.detect("/c/e.ics", before, before, me, zone).isEmpty())
    }

    @Test
    fun myOwnAnswerAndEventsOrganizedByOthersAreIgnored() {
        val before = invitation()
        val mine = EventWriter.respond(before, me, "DECLINED")
        if (mine != null) assertTrue(Replies.detect("/c/e.ics", before, mine, me, zone).isEmpty())
        val after = EventWriter.respond(before, "ana@example.org", "DECLINED")!!
        assertTrue(Replies.detect("/c/e.ics", before, after, "someone@else.org", zone).isEmpty())
    }
}
