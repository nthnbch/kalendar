package ch.nthnbch.kalendar

import ch.nthnbch.kalendar.dav.AuthException
import ch.nthnbch.kalendar.dav.CalDavClient
import ch.nthnbch.kalendar.dav.ConflictException
import ch.nthnbch.kalendar.dav.DavAccount
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** The HTTP side of create / read / update / delete, against a scripted server. */
class CalDavClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: CalDavClient

    @Before
    fun start() {
        server = MockWebServer().also { it.start() }
        client = CalDavClient(DavAccount(server.url("/").toString(), "user", "secret"))
    }

    @After
    fun stop() = server.shutdown()

    private fun url(path: String) = server.url(path).toString()

    @Test
    fun createUsesIfNoneMatchSoNothingIsOverwritten() {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"v1\""))
        val etag = client.put(url("/cal/new.ics"), "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n", etag = null)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("*", request.getHeader("If-None-Match"))
        assertNull(request.getHeader("If-Match"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("text/calendar"))
        assertEquals("\"v1\"", etag)
    }

    @Test
    fun updateIsConditionalOnTheEtagWeLastSaw() {
        server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"v2\""))
        client.put(url("/cal/e.ics"), "x", etag = "\"v1\"")
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-Match"))
    }

    @Test
    fun updateOfAChangedEventIsReportedAsAConflict() {
        server.enqueue(MockResponse().setResponseCode(412))
        try {
            client.put(url("/cal/e.ics"), "x", etag = "\"old\"")
            fail("expected a conflict")
        } catch (e: ConflictException) {
            assertEquals(412, e.code)
        }
    }

    @Test
    fun deleteSendsIfMatchAndTreatsMissingAsDone() {
        server.enqueue(MockResponse().setResponseCode(204))
        client.delete(url("/cal/e.ics"), "\"v1\"")
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("\"v1\"", request.getHeader("If-Match"))

        server.enqueue(MockResponse().setResponseCode(404))
        client.delete(url("/cal/gone.ics"), null)
    }

    @Test
    fun wrongPasswordIsAnAuthError() {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            client.put(url("/cal/e.ics"), "x", null)
            fail("expected an auth error")
        } catch (e: AuthException) {
            assertEquals(401, e.code)
        }
    }

    @Test
    fun requestsCarryBasicCredentials() {
        server.enqueue(MockResponse().setResponseCode(204))
        client.delete(url("/cal/e.ics"), null)
        // user:secret
        assertEquals("Basic dXNlcjpzZWNyZXQ=", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun readParsesEtagsFromAListingAndBodiesFromAMultiget() {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/cal/a.ics</d:href>
                   <d:propstat><d:prop><d:getetag>"a1"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""",
            ),
        )
        val listed = client.listEvents(url("/cal/"), Instant.parse("2026-01-01T00:00:00Z"))
        assertEquals(listOf("\"a1\""), listed.map { it.etag })
        assertTrue(listed.single().url.endsWith("/cal/a.ics"))
        assertTrue(server.takeRequest().body.readUtf8().contains("time-range"))

        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/cal/a.ics</d:href>
                   <d:propstat><d:prop><d:getetag>"a1"</d:getetag><c:calendar-data>BEGIN:VCALENDAR
END:VCALENDAR</c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""",
            ),
        )
        val fetched = client.fetch(url("/cal/"), listOf(url("/cal/a.ics")))
        assertTrue(fetched.single().data!!.startsWith("BEGIN:VCALENDAR"))
    }

    @Test
    fun serverAnswersWithDoctypeAreRejected() {
        server.enqueue(MockResponse().setResponseCode(207).setBody("""<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><d:multistatus xmlns:d="DAV:">&e;</d:multistatus>"""))
        try {
            client.listEvents(url("/cal/"), Instant.now())
            fail("expected a rejection")
        } catch (e: ch.nthnbch.kalendar.dav.DavException) {
            assertEquals("Unreadable answer from server", e.message)
        }
    }
}
