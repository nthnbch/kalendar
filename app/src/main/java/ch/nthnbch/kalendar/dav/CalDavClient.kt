package ch.nthnbch.kalendar.dav

import ch.nthnbch.kalendar.ical.EventParser
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

open class DavException(val code: Int, message: String) : IOException(message)
class AuthException : DavException(401, "Authentication refused")
class ConflictException(code: Int) : DavException(code, "The event changed on the server")

data class DavAccount(val baseUrl: String, val username: String, val password: String)
data class Discovery(val principalUrl: String, val homeUrl: String, val displayName: String?, val email: String?)
data class DavCalendar(val url: String, val name: String, val color: Int?, val ctag: String?, val readOnly: Boolean)
data class DavItem(val url: String, val etag: String?, val data: String? = null)

/** Minimal CalDAV client: just what is needed to mirror the calendars of a SabreDAV-style server. */
class CalDavClient(private val account: DavAccount, private val http: OkHttpClient = sharedHttp) {

    private val base: HttpUrl = account.baseUrl.trim().let { if (it.endsWith("/")) it else "$it/" }.toHttpUrlOrNull()
        ?: throw DavException(0, "Invalid server address")
    private val authorization = Credentials.basic(account.username, account.password, Charsets.UTF_8)

    fun discover(): Discovery {
        val principalHref = propfind(base, 0, "<d:current-user-principal/>")
            .firstNotNullOfOrNull { it.prop(DAV, "current-user-principal")?.child(DAV, "href")?.textContent?.trim() }
            ?: throw DavException(0, "No principal found on this server")
        val principal = resolve(base, principalHref)

        val props = propfind(
            principal, 0,
            "<c:calendar-home-set/><d:displayname/><c:calendar-user-address-set/>",
        ).firstOrNull() ?: throw DavException(0, "Principal not readable")
        val homeHref = props.prop(CALDAV, "calendar-home-set")?.child(DAV, "href")?.textContent?.trim()
            ?: throw DavException(0, "This account has no calendars")
        val email = props.prop(CALDAV, "calendar-user-address-set")?.children(DAV, "href")
            ?.map { it.textContent.trim() }
            ?.firstOrNull { it.startsWith("mailto:", ignoreCase = true) }?.substring(7)
        return Discovery(
            principalUrl = principal.toString(),
            homeUrl = resolve(principal, homeHref).toString(),
            displayName = props.prop(DAV, "displayname")?.textContent?.trim()?.takeIf { it.isNotEmpty() },
            email = email,
        )
    }

    fun calendars(homeUrl: String): List<DavCalendar> {
        val home = homeUrl.toHttpUrlOrNull() ?: throw DavException(0, "Invalid calendar home")
        val body = "<d:resourcetype/><d:displayname/><a:calendar-color/><cs:getctag/>" +
            "<c:supported-calendar-component-set/><d:current-user-privilege-set/>"
        return propfind(home, 1, body).mapNotNull { response ->
            val type = response.prop(DAV, "resourcetype") ?: return@mapNotNull null
            if (type.child(CALDAV, "calendar") == null) return@mapNotNull null
            val components = response.prop(CALDAV, "supported-calendar-component-set")
                ?.children(CALDAV, "comp")?.map { it.getAttribute("name").uppercase() }
            if (components != null && components.isNotEmpty() && "VEVENT" !in components) return@mapNotNull null

            val url = resolve(home, response.href).toString().let { if (it.endsWith("/")) it else "$it/" }
            val privileges = response.prop(DAV, "current-user-privilege-set")
                ?.children(DAV, "privilege")?.flatMap { p -> p.elements().map { it.localName } }
            val canWrite = privileges == null || privileges.any { it in WRITE_PRIVILEGES }
            DavCalendar(
                url = url,
                name = response.prop(DAV, "displayname")?.textContent?.trim()?.takeIf { it.isNotEmpty() }
                    ?: url.trimEnd('/').substringAfterLast('/'),
                color = EventParser.parseColor(response.prop(APPLE, "calendar-color")?.textContent),
                ctag = response.prop(CALENDARSERVER, "getctag")?.textContent?.trim(),
                readOnly = !canWrite,
            )
        }
    }

    /** Lists the events that still matter (anything ending after [since]) with their ETags, without bodies. */
    fun listEvents(calendarUrl: String, since: Instant): List<DavItem> {
        val url = calendarUrl.toHttpUrlOrNull() ?: throw DavException(0, "Invalid calendar address")
        val start = STAMP.format(since.atZone(ZoneOffset.UTC))
        val body = """<?xml version="1.0" encoding="utf-8"?>
<c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:prop><d:getetag/></d:prop>
  <c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VEVENT">
    <c:time-range start="$start"/>
  </c:comp-filter></c:comp-filter></c:filter>
</c:calendar-query>"""
        return multistatus(execute("REPORT", url, body, depth = 1)).filter { it.href.isNotEmpty() && it.ok }.map {
            DavItem(resolve(url, it.href).toString(), it.prop(DAV, "getetag")?.textContent?.trim())
        }
    }

    /** Downloads the given events. Missing ones are simply absent from the result. */
    fun fetch(calendarUrl: String, urls: List<String>): List<DavItem> {
        val calendar = calendarUrl.toHttpUrlOrNull() ?: throw DavException(0, "Invalid calendar address")
        val out = ArrayList<DavItem>()
        for (chunk in urls.chunked(40)) {
            val hrefs = chunk.joinToString("") { u -> "<d:href>${xml(u.toHttpUrlOrNull()?.encodedPath ?: u)}</d:href>" }
            val body = """<?xml version="1.0" encoding="utf-8"?>
<c:calendar-multiget xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:prop><d:getetag/><c:calendar-data/></d:prop>
  $hrefs
</c:calendar-multiget>"""
            for (response in multistatus(execute("REPORT", calendar, body, depth = 1))) {
                val data = response.prop(CALDAV, "calendar-data")?.textContent ?: continue
                out += DavItem(resolve(calendar, response.href).toString(), response.prop(DAV, "getetag")?.textContent?.trim(), data)
            }
        }
        return out
    }

    /**
     * Uploads an event. With an [etag] the write only succeeds if the server copy is still that version;
     * without one it only succeeds if nothing exists at that address yet.
     * Returns the new ETag when the server gives one.
     */
    fun put(url: String, ics: String, etag: String?): String? {
        val target = url.toHttpUrlOrNull() ?: throw DavException(0, "Invalid event address")
        val request = Request.Builder().url(target)
            .header("Authorization", authorization)
            .put(ics.toRequestBody(CALENDAR))
            .apply { if (etag != null) header("If-Match", etag) else header("If-None-Match", "*") }
            .build()
        http.newCall(request).execute().use { response ->
            check(response.code)
            return response.header("ETag")
        }
    }

    fun delete(url: String, etag: String?) {
        val target = url.toHttpUrlOrNull() ?: throw DavException(0, "Invalid event address")
        val request = Request.Builder().url(target)
            .header("Authorization", authorization)
            .delete()
            .apply { if (etag != null) header("If-Match", etag) }
            .build()
        http.newCall(request).execute().use { response ->
            // Already gone is exactly what was asked for.
            if (response.code != 404) check(response.code)
        }
    }

    private fun propfind(url: HttpUrl, depth: Int, props: String): List<DavResponse> {
        val body = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/" xmlns:a="http://apple.com/ns/ical/">
  <d:prop>$props</d:prop>
</d:propfind>"""
        return multistatus(execute("PROPFIND", url, body, depth))
    }

    private fun execute(method: String, url: HttpUrl, body: String, depth: Int): String {
        val request = Request.Builder().url(url)
            .header("Authorization", authorization)
            .header("Depth", depth.toString())
            .method(method, body.toRequestBody(XML))
            .build()
        http.newCall(request).execute().use { response ->
            check(response.code)
            return response.body?.string().orEmpty()
        }
    }

    private fun check(code: Int) {
        when {
            code in 200..299 -> Unit
            code == 401 -> throw AuthException()
            code == 403 -> throw DavException(code, "Not allowed on this calendar")
            code == 409 || code == 412 -> throw ConflictException(code)
            else -> throw DavException(code, "Server answered $code")
        }
    }

    private fun resolve(from: HttpUrl, href: String): HttpUrl =
        from.resolve(href) ?: throw DavException(0, "Unreadable address from server: $href")

    private class DavResponse(val href: String, val ok: Boolean, private val found: Map<String, Element>) {
        fun prop(namespace: String, name: String): Element? = found["$namespace|$name"]
    }

    private fun multistatus(xml: String): List<DavResponse> {
        if (xml.isBlank()) return emptyList()
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // The document comes from the network: never resolve entities or external DTDs.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { isExpandEntityReferences = false }
        }
        val root = try {
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        } catch (e: Exception) {
            throw DavException(0, "Unreadable answer from server")
        }
        return root.children(DAV, "response").map { response ->
            val found = HashMap<String, Element>()
            for (propstat in response.children(DAV, "propstat")) {
                val status = propstat.child(DAV, "status")?.textContent.orEmpty()
                if (status.isNotEmpty() && " 200" !in status) continue
                propstat.child(DAV, "prop")?.elements()?.forEach { found["${it.namespaceURI}|${it.localName}"] = it }
            }
            val status = response.child(DAV, "status")?.textContent.orEmpty()
            DavResponse(
                href = response.child(DAV, "href")?.textContent?.trim().orEmpty(),
                ok = status.isEmpty() || " 200" in status,
                found = found,
            )
        }
    }

    companion object {
        const val DAV = "DAV:"
        const val CALDAV = "urn:ietf:params:xml:ns:caldav"
        const val CALENDARSERVER = "http://calendarserver.org/ns/"
        const val APPLE = "http://apple.com/ns/ical/"

        private val WRITE_PRIVILEGES = setOf("all", "write", "write-content", "bind")
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val CALENDAR = "text/calendar; charset=utf-8".toMediaType()
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

        val sharedHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        private fun xml(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}

private fun Element.elements(): List<Element> {
    val out = ArrayList<Element>()
    var node = firstChild
    while (node != null) {
        if (node is Element) out.add(node)
        node = node.nextSibling
    }
    return out
}

private fun Element.children(namespace: String, name: String): List<Element> =
    elements().filter { it.namespaceURI == namespace && it.localName == name }

private fun Element.child(namespace: String, name: String): Element? = children(namespace, name).firstOrNull()
