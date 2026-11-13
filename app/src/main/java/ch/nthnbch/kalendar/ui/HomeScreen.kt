package ch.nthnbch.kalendar.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.nthnbch.kalendar.R
import ch.nthnbch.kalendar.data.Repository
import ch.nthnbch.kalendar.data.Snapshot
import ch.nthnbch.kalendar.data.SyncOutcome
import ch.nthnbch.kalendar.dav.ConflictException
import ch.nthnbch.kalendar.ical.Occurrence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** What the editor was opened for: a new event on [date], or a change to [occurrence]. */
data class EditorRequest(val date: LocalDate, val occurrence: Occurrence? = null)

private const val PAGES = 2400
private const val HOME_PAGE = PAGES / 2

fun Snapshot.colorOf(o: Occurrence): Color = Color(o.event.color ?: calendar(o.calendarUrl)?.color ?: 0xFF0098FF.toInt())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(repository: Repository, link: Link?, onLinkHandled: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by repository.state.collectAsStateWithLifecycle()
    val syncing by repository.syncing.collectAsStateWithLifecycle()
    val authFailed by repository.authFailed.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var today by remember { mutableStateOf(LocalDate.now()) }
    var selectedEpochDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val selected = LocalDate.ofEpochDay(selectedEpochDay)
    var agendaMode by rememberSaveable { mutableStateOf(false) }
    var showCalendars by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Occurrence?>(null) }
    var editor by remember { mutableStateOf<EditorRequest?>(null) }

    suspend fun report(outcome: SyncOutcome) {
        if (outcome is SyncOutcome.Failed) snackbar.showSnackbar(context.getString(R.string.sync_failed, outcome.message))
    }

    LaunchedEffect(Unit) { repository.ensureLoaded() }

    // Coming back to the app: the day may have changed and the server too.
    LifecycleResumeEffect(Unit) {
        today = LocalDate.now()
        if (System.currentTimeMillis() - repository.lastSync > 2 * 60_000) scope.launch { report(repository.sync()) }
        onPauseOrDispose { }
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    LaunchedEffect(authFailed) {
        if (authFailed) {
            val result = snackbar.showSnackbar(
                context.getString(R.string.auth_expired), context.getString(R.string.sign_in_again), duration = androidx.compose.material3.SnackbarDuration.Indefinite,
            )
            if (result == SnackbarResult.ActionPerformed) repository.signOut()
        }
    }

    LaunchedEffect(link, snapshot.loaded) {
        val target = link ?: return@LaunchedEffect
        if (!snapshot.loaded) return@LaunchedEffect
        when (target) {
            is Link.Day -> selectedEpochDay = target.date.toEpochDay()
            is Link.New -> {
                selectedEpochDay = target.date.toEpochDay()
                editor = EditorRequest(target.date)
            }
            is Link.Event -> {
                val day = java.time.Instant.ofEpochSecond(target.startEpochSecond).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                selectedEpochDay = day.toEpochDay()
                detail = snapshot.occurrences(day.minusDays(1), day.plusDays(2))
                    .firstOrNull { it.href == target.href && it.startInstant.epochSecond == target.startEpochSecond }
            }
        }
        onLinkHandled()
    }

    val request = editor
    if (request != null) {
        EventEditor(
            snapshot = snapshot,
            request = request,
            onClose = { editor = null },
            onSave = { calendarUrl, draft, resource ->
                try {
                    repository.save(calendarUrl, draft, resource)
                    editor = null
                    null
                } catch (e: ConflictException) {
                    context.getString(R.string.conflict)
                } catch (e: Exception) {
                    context.getString(R.string.save_failed, e.message ?: e.javaClass.simpleName)
                }
            },
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { showCalendars = true }) { Icon(Icons.Filled.Menu, stringResource(R.string.calendars)) }
                    },
                    title = {
                        Text(
                            Format.month(YearMonth.from(selected), withYear = selected.year != today.year),
                            fontWeight = FontWeight.SemiBold, maxLines = 1,
                        )
                    },
                    actions = {
                        IconButton(onClick = { selectedEpochDay = today.toEpochDay() }) {
                            Icon(painterResource(R.drawable.ic_today), stringResource(R.string.today))
                        }
                        IconButton(onClick = { agendaMode = !agendaMode }) {
                            Icon(
                                painterResource(if (agendaMode) R.drawable.ic_month else R.drawable.ic_agenda),
                                stringResource(if (agendaMode) R.string.view_month else R.string.view_agenda),
                            )
                        }
                    },
                )
                if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) else Spacer(Modifier.height(2.dp))
            }
        },
        floatingActionButton = {
            if (snapshot.calendars.any { !it.readOnly }) {
                FloatingActionButton(onClick = { editor = EditorRequest(selected) }) { Icon(Icons.Filled.Add, stringResource(R.string.new_event)) }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (agendaMode) {
                AgendaView(snapshot, today, selected, onSelect = { selectedEpochDay = it.toEpochDay() }, onOpen = { detail = it })
            } else {
                MonthView(snapshot, today, selected, onSelect = { selectedEpochDay = it.toEpochDay() }, onOpen = { detail = it })
            }
        }
    }

    detail?.let { occurrence ->
        EventDetailSheet(
            snapshot = snapshot,
            occurrence = occurrence,
            onDismiss = { detail = null },
            onEdit = {
                detail = null
                editor = EditorRequest(occurrence.firstDay, occurrence)
            },
            onDelete = { onlyThisOne ->
                detail = null
                scope.launch {
                    try {
                        repository.delete(occurrence, onlyThisOne)
                    } catch (e: ConflictException) {
                        snackbar.showSnackbar(context.getString(R.string.conflict))
                    } catch (e: Exception) {
                        snackbar.showSnackbar(context.getString(R.string.delete_failed, e.message ?: e.javaClass.simpleName))
                    }
                }
            },
        )
    }

    if (showCalendars) {
        CalendarsSheet(
            repository = repository,
            snapshot = snapshot,
            onDismiss = { showCalendars = false },
            onSync = { scope.launch { report(repository.sync(force = true)) } },
        )
    }

    BackHandler(enabled = agendaMode) { agendaMode = false }
}

@Composable
private fun rememberOccurrences(snapshot: Snapshot, from: LocalDate, to: LocalDate): List<Occurrence>? {
    val result by produceState<List<Occurrence>?>(null, snapshot, from, to) {
        value = withContext(Dispatchers.Default) { snapshot.occurrences(from, to) }
    }
    return result
}

/* ------------------------------------------------------------------ Month */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MonthView(snapshot: Snapshot, today: LocalDate, selected: LocalDate, onSelect: (LocalDate) -> Unit, onOpen: (Occurrence) -> Unit) {
    val origin = remember { YearMonth.now() }
    fun monthAt(page: Int): YearMonth = origin.plusMonths((page - HOME_PAGE).toLong())
    fun pageOf(month: YearMonth): Int = HOME_PAGE + ChronoUnit.MONTHS.between(origin, month).toInt()

    val pager = rememberPagerState(initialPage = pageOf(YearMonth.from(selected))) { PAGES }

    // Swiping selects a day in the new month; selecting a day elsewhere brings its month into view.
    LaunchedEffect(pager.settledPage) {
        val month = monthAt(pager.settledPage)
        if (YearMonth.from(selected) != month) onSelect(if (month == YearMonth.from(today)) today else month.atDay(1))
    }
    LaunchedEffect(selected) {
        val page = pageOf(YearMonth.from(selected))
        if (page != pager.currentPage && !pager.isScrollInProgress) pager.animateScrollToPage(page)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            for (weekday in Format.weekdays()) {
                Text(
                    Format.weekdayShort(weekday),
                    Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalPager(pager, Modifier.fillMaxWidth().weight(1.3f), beyondViewportPageCount = 1) { page ->
            MonthGrid(snapshot, monthAt(page), today, selected, onSelect)
        }
        HorizontalDivider()
        DayList(snapshot, selected, today, onOpen, Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun MonthGrid(snapshot: Snapshot, month: YearMonth, today: LocalDate, selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    val start = remember(month) { Format.gridStart(month) }
    val weeks = remember(month) { Format.gridWeeks(month) }
    val occurrences = rememberOccurrences(snapshot, start, start.plusDays(weeks * 7L)).orEmpty()

    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        val cellHeight = maxHeight / weeks
        // A chip is 15dp high plus 2dp of spacing; the day number takes about 30dp.
        val chips = ((cellHeight - 30.dp) / 17.dp).toInt().coerceIn(0, 4)
        Column(Modifier.fillMaxSize()) {
            for (week in 0 until weeks) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    for (d in 0 until 7) {
                        val day = start.plusDays((week * 7 + d).toLong())
                        val ofDay = occurrences.filter { it.covers(day) }
                        DayCell(
                            day = day, today = today, selected = day == selected, inMonth = YearMonth.from(day) == month,
                            occurrences = ofDay, chips = chips, snapshot = snapshot,
                            modifier = Modifier.weight(1f).fillMaxHeight().clickable { onSelect(day) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate, today: LocalDate, selected: Boolean, inMonth: Boolean,
    occurrences: List<Occurrence>, chips: Int, snapshot: Snapshot, modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val isToday = day == today
    Column(modifier.padding(horizontal = 1.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.padding(top = 2.dp, bottom = 2.dp).size(26.dp).clip(CircleShape)
                .then(
                    when {
                        isToday -> Modifier.background(colors.primary)
                        selected -> Modifier.border(1.5.dp, colors.primary, CircleShape)
                        else -> Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.dayOfMonth.toString(),
                fontSize = 13.sp,
                fontWeight = if (isToday || selected) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    isToday -> colors.onPrimary
                    inMonth -> colors.onSurface
                    else -> colors.onSurface.copy(alpha = 0.38f)
                },
            )
        }
        if (chips == 0) {
            // Too small for titles: a row of dots still tells which days are busy.
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                occurrences.map { snapshot.colorOf(it) }.distinct().take(3).forEach { Box(Modifier.size(5.dp).clip(CircleShape).background(it)) }
            }
            return@Column
        }
        val overflow = occurrences.size > chips
        val shown = if (overflow) occurrences.take(chips - 1) else occurrences
        for (o in shown) {
            val color = snapshot.colorOf(o)
            Text(
                o.event.summary.ifBlank { stringResource(R.string.untitled) },
                Modifier.fillMaxWidth().padding(bottom = 2.dp).height(15.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (o.allDay) color.copy(alpha = if (inMonth) 0.85f else 0.4f) else color.copy(alpha = if (inMonth) 0.20f else 0.10f))
                    .padding(horizontal = 3.dp),
                fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Clip, softWrap = false,
                color = if (o.allDay) Color.White else colors.onSurface.copy(alpha = if (inMonth) 1f else 0.5f),
            )
        }
        if (overflow) {
            Text("+${occurrences.size - shown.size}", fontSize = 10.sp, lineHeight = 14.sp, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun DayList(snapshot: Snapshot, day: LocalDate, today: LocalDate, onOpen: (Occurrence) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val occurrences = rememberOccurrences(snapshot, day, day.plusDays(1))
    LazyColumn(modifier, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text(
                Format.dayTitle(context, day, today), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (occurrences != null && occurrences.isEmpty()) {
            item { Text(stringResource(R.string.nothing_planned), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
        }
        items(occurrences.orEmpty(), key = { it.href + it.startInstant.epochSecond }) { o -> EventRow(snapshot, o, day, onOpen) }
    }
}

@Composable
fun EventRow(snapshot: Snapshot, o: Occurrence, day: LocalDate, onOpen: (Occurrence) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val color = snapshot.colorOf(o)
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.14f)).clickable { onOpen(o) }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                o.event.summary.ifBlank { stringResource(R.string.untitled) },
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                Format.whenAndWhere(context, o, day),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/* ----------------------------------------------------------------- Agenda */

private data class AgendaDay(val day: LocalDate, val occurrences: List<Occurrence>)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgendaView(snapshot: Snapshot, today: LocalDate, selected: LocalDate, onSelect: (LocalDate) -> Unit, onOpen: (Occurrence) -> Unit) {
    val context = LocalContext.current
    // The window follows the selection so that any day reached from the month view or a widget is in it.
    val anchor = remember(selected.withDayOfMonth(1)) { selected }
    val from = minOf(anchor, today).minusDays(30)
    val to = maxOf(anchor, today).plusDays(366)
    val occurrences = rememberOccurrences(snapshot, from, to)
    val days = remember(occurrences, today) {
        val byDay = HashMap<LocalDate, MutableList<Occurrence>>()
        for (o in occurrences.orEmpty()) {
            var day = maxOf(o.firstDay, from)
            val last = minOf(o.lastDay, to.minusDays(1))
            while (!day.isAfter(last)) {
                byDay.getOrPut(day) { ArrayList() }.add(o)
                day = day.plusDays(1)
            }
        }
        if (today !in byDay) byDay[today] = ArrayList()
        byDay.entries.sortedBy { it.key }.map { AgendaDay(it.key, it.value) }
    }

    val list = rememberLazyListState()
    var positioned by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(selected, occurrences != null) {
        if (occurrences == null || positioned == selected) return@LaunchedEffect
        val index = days.indexOfFirst { !it.day.isBefore(selected) }
        if (index >= 0) list.scrollToItem(index)
        positioned = selected
    }

    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 96.dp)) {
        for (entry in days) {
            item(key = entry.day.toEpochDay()) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)) {
                    val isToday = entry.day == today
                    Column(Modifier.width(52.dp).clickable { onSelect(entry.day) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            Format.weekdayShort(entry.day.dayOfWeek).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).then(if (isToday) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                entry.day.dayOfMonth.toString(), fontWeight = FontWeight.Bold, fontSize = 16.sp,
                                color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (entry.day.dayOfMonth == 1 || entry == days.first()) {
                            Text(Format.month(YearMonth.from(entry.day), withYear = false).take(4), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (entry.occurrences.isEmpty()) {
                            Text(
                                context.getString(R.string.nothing_planned), Modifier.padding(vertical = 14.dp, horizontal = 10.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        for (o in entry.occurrences) EventRow(snapshot, o, entry.day, onOpen)
                    }
                }
            }
        }
    }
}
