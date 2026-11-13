package ch.nthnbch.kalendar.data

import android.content.Context
import ch.nthnbch.kalendar.dav.AuthException
import ch.nthnbch.kalendar.dav.CalDavClient
import ch.nthnbch.kalendar.dav.ConflictException
import ch.nthnbch.kalendar.dav.DavAccount
import ch.nthnbch.kalendar.ical.EventDraft
import ch.nthnbch.kalendar.ical.EventParser
import ch.nthnbch.kalendar.ical.EventResource
import ch.nthnbch.kalendar.ical.EventWriter
import ch.nthnbch.kalendar.ical.Occurrence
import ch.nthnbch.kalendar.ical.Recurrence
import ch.nthnbch.kalendar.ical.Scope
import ch.nthnbch.kalendar.widget.Widgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Everything the screens and widgets draw from. Immutable: a change publishes a new one. */
data class Snapshot(
    val calendars: List<CalendarInfo> = emptyList(),
    val resources: List<EventResource> = emptyList(),
    val loaded: Boolean = false,
) {
    private val byUrl: Map<String, CalendarInfo> by lazy { calendars.associateBy { it.url } }
    fun calendar(url: String): CalendarInfo? = byUrl[url]
    fun resource(href: String): EventResource? = resources.firstOrNull { it.href == href }

    /** Occurrences of the visible calendars touching `[from, to)`, in display order. */
    fun occurrences(from: LocalDate, to: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Occurrence> {
        val start = from.atStartOfDay(zone).toInstant()
        val end = to.atStartOfDay(zone).toInstant()
        val visible = calendars.filter { it.visible }.mapTo(HashSet()) { it.url }
        return resources.asSequence()
            .filter { it.calendarUrl in visible }
            .flatMap { Recurrence.expand(it, start, end, zone) }
            .sortedWith(DISPLAY_ORDER)
            .toList()
    }

    companion object {
        val DISPLAY_ORDER: Comparator<Occurrence> = compareBy<Occurrence> { it.firstDay }
            .thenBy { !it.allDay }
            .thenBy { it.start }
            .thenBy { it.event.summary.lowercase() }
    }
}

sealed interface SyncOutcome {
    data object Success : SyncOutcome
    data object NotSignedIn : SyncOutcome
    data object AuthFailed : SyncOutcome
    /** No connection (or the server cannot be reached): the stored copy is shown, nothing is lost. */
    data object Offline : SyncOutcome
    data class Failed(val message: String) : SyncOutcome
}

class Repository private constructor(private val context: Context) {

    private val db = Db(context)
    private val accounts = AccountStore(context)
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val syncMutex = Mutex()
    private val loadMutex = Mutex()

    /** Parsed events by href, reused as long as the stored text is unchanged. */
    private val parsed = HashMap<String, EventResource>()

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state

    private val _account = MutableStateFlow(accounts.load())
    val account: StateFlow<Account?> = _account

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    /** True when the server refused the stored password: the user has to sign in again. */
    private val _authFailed = MutableStateFlow(false)
    val authFailed: StateFlow<Boolean> = _authFailed

    val lastSync: Long get() = prefs.getLong("last", 0L)

    suspend fun ensureLoaded(): Snapshot {
        if (_state.value.loaded) return _state.value
        return loadMutex.withLock {
            if (!_state.value.loaded) reload()
            _state.value
        }
    }

    private suspend fun reload() = withContext(Dispatchers.IO) {
        val calendars = db.calendars()
        val stored = db.events()
        val zone = ZoneId.systemDefault()
        val resources = synchronized(parsed) {
            val fresh = HashMap<String, EventResource>(stored.size)
            for (event in stored) {
                val known = parsed[event.href]
                fresh[event.href] = if (known != null && known.ics == event.ics && known.calendarUrl == event.calendarUrl) {
                    if (known.etag == event.etag) known else known.copy(etag = event.etag)
                } else {
                    EventResource(event.href, event.calendarUrl, event.etag, event.ics, EventParser.parse(event.ics, zone))
                }
            }
            parsed.clear()
            parsed.putAll(fresh)
            fresh.values.toList()
        }
        _state.value = Snapshot(calendars, resources, loaded = true)
    }

    /** Checks the credentials against the server, stores them and runs the first synchronisation. */
    suspend fun signIn(server: String, username: String, password: String): SyncOutcome = withContext(Dispatchers.IO) {
        try {
            val dav = DavAccount(server.trim(), username.trim(), password.trim())
            val discovery = CalDavClient(dav).discover()
            db.wipe()
            accounts.save(Account(dav.baseUrl, dav.username, dav.password, discovery.homeUrl, discovery.displayName, discovery.email))
            _account.value = accounts.load()
            _authFailed.value = false
        } catch (e: AuthException) {
            return@withContext SyncOutcome.AuthFailed
        } catch (e: Exception) {
            return@withContext SyncOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
        sync(force = true)
    }

    // Not cancellable: the screen that asked for it disappears as soon as the account does.
    /** Finishes "Sign in with Infomaniak": turns the authorization [code] into CalDAV credentials and signs in. */
    suspend fun signInWithInfomaniak(code: String): SyncOutcome {
        val credentials = try {
            withContext(Dispatchers.IO) { InfomaniakAuth.credentials(context, code) }
        } catch (e: Exception) {
            return SyncOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
        return signIn(DEFAULT_SERVER, credentials.login, credentials.password)
    }

    suspend fun signOut() = withContext(NonCancellable + Dispatchers.IO) {
        syncMutex.withLock {
            accounts.clear()
            db.wipe()
            prefs.edit().clear().apply()
            _account.value = null
            _authFailed.value = false
            reload()
        }
        afterChange()
    }

    suspend fun sync(force: Boolean = false): SyncOutcome = withContext(Dispatchers.IO) {
        val account = _account.value ?: return@withContext SyncOutcome.NotSignedIn
        if (!Network.isOnline(context)) return@withContext SyncOutcome.Offline
        syncMutex.withLock {
            _syncing.value = true
            try {
                ensureLoaded()
                pull(CalDavClient(account.dav), account, force)
                prefs.edit().putLong("last", System.currentTimeMillis()).apply()
                _authFailed.value = false
                SyncOutcome.Success
            } catch (e: AuthException) {
                _authFailed.value = true
                SyncOutcome.AuthFailed
            } catch (e: Exception) {
                if (Network.isConnectivityFailure(e)) SyncOutcome.Offline
                else SyncOutcome.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                reload()
                _syncing.value = false
                afterChange()
            }
        }
    }

    private fun pull(client: CalDavClient, account: Account, force: Boolean) {
        val remote = client.calendars(account.homeUrl)
        val local = db.calendars().associateBy { it.url }

        db.transaction {
            remote.forEachIndexed { index, calendar ->
                db.upsertCalendar(calendar.url, calendar.name, calendar.color ?: PALETTE[index % PALETTE.size], calendar.readOnly)
            }
            val remaining = remote.mapTo(HashSet()) { it.url }
            local.keys.filter { it !in remaining }.forEach(db::deleteCalendar)
        }

        val since = Instant.now().minus(HISTORY_DAYS, ChronoUnit.DAYS)
        for (calendar in remote) {
            val known = local[calendar.url]
            // The ctag changes whenever anything in the calendar does: same ctag, nothing to download.
            if (!force && known?.ctag != null && known.ctag == calendar.ctag) continue

            val listed = client.listEvents(calendar.url, since)
            val etags = db.etags(calendar.url)
            val changed = listed.filter { it.etag == null || etags[it.url] != it.etag }.map { it.url }
            val downloaded = client.fetch(calendar.url, changed)
            val replies = account.email?.let { me ->
                val zone = ZoneId.systemDefault()
                downloaded.flatMap { item ->
                    val data = item.data ?: return@flatMap emptyList()
                    val before = db.ics(item.url) ?: return@flatMap emptyList()
                    runCatching { Replies.detect(item.url, before, data, me, zone) }.getOrDefault(emptyList())
                }
            }.orEmpty()
            db.transaction {
                for (item in downloaded) {
                    db.upsertEvent(StoredEvent(item.url, calendar.url, item.etag, item.data.orEmpty()))
                }
                val listedUrls = listed.mapTo(HashSet()) { it.url }
                etags.keys.filter { it !in listedUrls }.forEach(db::deleteEvent)
                db.setCtag(calendar.url, calendar.ctag)
            }
            Replies.notify(context, replies)
        }
    }

    suspend fun setVisible(url: String, visible: Boolean) = withContext(Dispatchers.IO) {
        db.setVisible(url, visible)
        reload()
        afterChange()
    }

    /**
     * Creates the event, or rewrites [existing]. Throws on failure so the editor can keep the user's input.
     */
    suspend fun save(
        calendarUrl: String,
        draft: EventDraft,
        existing: EventResource?,
        scope: Scope = Scope.ALL,
        occurrence: Occurrence? = null,
    ) = withContext(Dispatchers.IO) {
        val account = _account.value ?: throw IllegalStateException("Not signed in")
        if (!Network.isOnline(context)) throw OfflineException()
        val client = CalDavClient(account.dav)
        val draft = draft.copy(organizerEmail = account.email)
        val zone = ZoneId.systemDefault()
        val newUid = EventWriter.newUid()
        val split = if (existing != null && scope == Scope.FOLLOWING && occurrence != null) {
            EventWriter.splitSeries(existing.ics, draft, slotOf(existing, occurrence), zone, newUid)
        } else null
        try {
            if (existing == null) {
                store(client, calendarUrl, "$calendarUrl$newUid.ics", EventWriter.create(draft, newUid), etag = null)
            } else if (scope == Scope.THIS && occurrence != null) {
                val slot = slotOf(existing, occurrence)
                val ics = EventWriter.updateOccurrence(existing.ics, draft, slot, zone) ?: throw IllegalStateException("Unreadable event")
                store(client, existing.calendarUrl, existing.href, ics, existing.etag)
            } else if (split != null && existing != null) {
                // The new series first: if the second write fails the user sees a duplicate, never a lost event.
                store(client, existing.calendarUrl, "${existing.calendarUrl}$newUid.ics", split.created, etag = null)
                store(client, existing.calendarUrl, existing.href, split.truncated, existing.etag)
            } else {
                val ics = EventWriter.update(existing.ics, draft) ?: throw IllegalStateException("Unreadable event")
                if (existing.calendarUrl == calendarUrl) {
                    store(client, calendarUrl, existing.href, ics, existing.etag)
                } else {
                    // Moving to another calendar: write the copy first so a failure never loses the event.
                    val name = existing.href.trimEnd('/').substringAfterLast('/')
                    store(client, calendarUrl, calendarUrl + name, ics, etag = null)
                    client.delete(existing.href, existing.etag)
                    db.deleteEvent(existing.href)
                    db.setCtag(existing.calendarUrl, null)
                }
            }
        } catch (e: ConflictException) {
            sync(force = true)
            throw e
        } finally {
            reload()
            afterChange()
        }
    }

    /** Deletes the whole event, or just [occurrence] when [onlyThisOne] is set on a recurring event. */
    suspend fun delete(occurrence: Occurrence, scope: Scope) = withContext(Dispatchers.IO) {
        val account = _account.value ?: throw IllegalStateException("Not signed in")
        if (!Network.isOnline(context)) throw OfflineException()
        val resource = _state.value.resource(occurrence.href) ?: return@withContext
        val client = CalDavClient(account.dav)
        try {
            val zone = ZoneId.systemDefault()
            val without = when (scope) {
                Scope.ALL -> null
                Scope.THIS -> EventWriter.excludeOccurrence(resource.ics, slotOf(resource, occurrence), zone)
                Scope.FOLLOWING -> EventWriter.truncateSeries(resource.ics, slotOf(resource, occurrence), zone)
            }

            if (without != null) {
                store(client, resource.calendarUrl, resource.href, without, resource.etag)
            } else {
                client.delete(resource.href, resource.etag)
                db.deleteEvent(resource.href)
                db.setCtag(resource.calendarUrl, null)
            }
        } catch (e: ConflictException) {
            sync(force = true)
            throw e
        } finally {
            reload()
            afterChange()
        }
    }

    /** Answers an invitation as the signed-in user; the server forwards the reply to the organizer. */
    suspend fun respond(href: String, status: String) = withContext(Dispatchers.IO) {
        val account = _account.value ?: throw IllegalStateException("Not signed in")
        if (!Network.isOnline(context)) throw OfflineException()
        val email = account.email ?: throw IllegalStateException("Unknown address")
        val resource = _state.value.resource(href) ?: return@withContext
        val ics = EventWriter.respond(resource.ics, email, status) ?: throw IllegalStateException("Not invited")
        try {
            store(CalDavClient(account.dav), resource.calendarUrl, resource.href, ics, resource.etag)
        } catch (e: ConflictException) {
            sync(force = true)
            throw e
        } finally {
            reload()
            afterChange()
        }
    }

    /** Start of the slot an occurrence fills in its series, in the master's own zone (what EXDATE / RECURRENCE-ID name). */
    private fun slotOf(resource: EventResource, occurrence: Occurrence): java.time.LocalDateTime {
        val key = occurrence.event.recurrenceKey ?: return occurrence.originalStart
        val master = resource.events.firstOrNull { it.recurrenceKey == null } ?: return occurrence.originalStart
        return Recurrence.originalStartOf(master, key, ZoneId.systemDefault())
    }

    private fun store(client: CalDavClient, calendarUrl: String, url: String, ics: String, etag: String?) {
        val returned = client.put(url, ics, etag)
        // Read it back: the server may rewrite the event, and only its own ETag is safe for the next edit.
        val saved = runCatching { client.fetch(calendarUrl, listOf(url)).firstOrNull() }.getOrNull()
        db.upsertEvent(StoredEvent(url, calendarUrl, saved?.etag ?: returned, saved?.data ?: ics))
        db.setCtag(calendarUrl, null)
    }

    private suspend fun afterChange() {
        Alarms.reschedule(context, _state.value)
        Widgets.refresh(context)
    }

    companion object {
        const val DEFAULT_SERVER = "https://sync.infomaniak.com"
        private const val HISTORY_DAYS = 365L

        /** Used when the server gives a calendar no color. */
        private val PALETTE = intArrayOf(
            0xFF0098FF.toInt(), 0xFFE91E63.toInt(), 0xFF3EBF4D.toInt(), 0xFFFF9802.toInt(),
            0xFF9C26B0.toInt(), 0xFF00BCD4.toInt(), 0xFFF44336.toInt(), 0xFF607D8B.toInt(),
        )

        @Volatile
        private var instance: Repository? = null

        fun get(context: Context): Repository = instance ?: synchronized(this) {
            instance ?: Repository(context.applicationContext).also { instance = it }
        }
    }
}
