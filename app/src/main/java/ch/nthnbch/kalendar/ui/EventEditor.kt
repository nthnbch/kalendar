package ch.nthnbch.kalendar.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import ch.nthnbch.kalendar.R
import ch.nthnbch.kalendar.data.Snapshot
import ch.nthnbch.kalendar.ical.EventDraft
import ch.nthnbch.kalendar.ical.EventResource
import ch.nthnbch.kalendar.ical.Scope
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private val REPEATS = listOf(null to R.string.repeat_never, "FREQ=DAILY" to R.string.repeat_daily, "FREQ=WEEKLY" to R.string.repeat_weekly, "FREQ=MONTHLY" to R.string.repeat_monthly, "FREQ=YEARLY" to R.string.repeat_yearly)

/** Hour at which a day begins: where a new event starts unless it is created for the rest of today. */
private const val DAY_START_HOUR = 6
private val DEFAULT_DURATION: Duration = Duration.ofHours(1)
/** Minutes before the start. */
private const val DEFAULT_REMINDER = 60
private val REMINDERS = listOf(0, 5, 10, 15, 30, 60, 120, 1440, 2880, 10080)

/**
 * Full-screen form. [onSave] returns an error message, or null once the event is stored (the caller then closes the editor).
 * A recurring event is always edited as a whole series, from its first occurrence.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditor(
    snapshot: Snapshot,
    request: EditorRequest,
    myEmail: String?,
    onClose: () -> Unit,
    onSave: suspend (calendarUrl: String, draft: EventDraft, resource: EventResource?) -> String?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("editor", android.content.Context.MODE_PRIVATE) }
    val resource = remember { request.occurrence?.let { snapshot.resource(it.href) } }
    val scopeNow = request.scope
    val master = remember { resource?.events?.firstOrNull { it.recurrenceKey == null } ?: request.occurrence?.event }
    val source = remember {
        val o = request.occurrence
        when {
            o == null || scopeNow == Scope.ALL || master == null -> master
            // An override carries its own fields; a plain occurrence borrows the series' and moves to its own date.
            o.event.recurrenceKey != null -> o.event
            else -> master.copy(start = o.originalStart, end = o.originalStart.plus(Duration.between(master.start, master.end)))
        }
    }
    val isOrganizer = remember { source?.organizerEmail == null || source.organizerEmail.equals(myEmail, ignoreCase = true) }
    val writable = remember(snapshot) { snapshot.calendars.filter { !it.readOnly } }
    val zone = remember { source?.zone ?: ZoneId.systemDefault() }

    val defaultStart = remember {
        // A day starts at 06:00. Today, a new event starts at the next full hour (but not before that).
        val hour = if (request.date == LocalDate.now()) (LocalTime.now().hour + 1).coerceIn(DAY_START_HOUR, 23) else DAY_START_HOUR
        request.date.atStartOfDay().plusHours(hour.toLong())
    }
    var title by remember { mutableStateOf(source?.summary.orEmpty()) }
    var location by remember { mutableStateOf(source?.location.orEmpty()) }
    var notes by remember { mutableStateOf(source?.description.orEmpty()) }
    var allDay by remember { mutableStateOf(source?.allDay ?: false) }
    var start by remember { mutableStateOf(source?.start ?: defaultStart) }
    // For all-day events the form shows the last day itself; the stored end is the day after.
    var end by remember { mutableStateOf(source?.let { if (it.allDay) it.end.minusDays(1) else it.end } ?: defaultStart.plus(DEFAULT_DURATION)) }
    var rrule by remember { mutableStateOf(if (scopeNow == Scope.THIS) null else source?.rrule ?: master?.rrule?.takeIf { scopeNow != Scope.ALL }) }
    var guests by remember { mutableStateOf(source?.attendees?.joinToString(", ") { it.email }.orEmpty()) }
    var alarms by remember { mutableStateOf(source?.alarms ?: listOf(DEFAULT_REMINDER)) }
    var calendarUrl by remember {
        mutableStateOf(
            resource?.calendarUrl
                ?: prefs.getString("calendar", null)?.takeIf { url -> writable.any { it.url == url } }
                ?: writable.firstOrNull()?.url.orEmpty(),
        )
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<String?>(null) }

    fun setStart(value: LocalDateTime) {
        // Moving the start keeps the length, so the end never ends up before the start.
        val length = Duration.between(start, end)
        start = value
        end = value.plus(if (length.isNegative) Duration.ZERO else length)
    }

    fun submit() {
        if (busy || calendarUrl.isEmpty()) return
        busy = true
        error = null
        val draft = EventDraft(
            title = title.trim(), location = location, description = notes, allDay = allDay,
            start = if (allDay) start.toLocalDate().atStartOfDay() else start,
            end = when {
                allDay -> maxOf(end.toLocalDate(), start.toLocalDate()).plusDays(1).atStartOfDay()
                end.isBefore(start) -> start
                else -> end
            },
            zone = zone, rrule = rrule, alarms = alarms,
            guests = if (isOrganizer) guests.split(',', ';', '\n', ' ').map { it.trim() }.filter { "@" in it } else null,
        )
        scope.launch {
            prefs.edit().putString("calendar", calendarUrl).apply()
            error = onSave(calendarUrl, draft, resource)
            busy = false
        }
    }

    BackHandler(onBack = onClose)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) } },
                title = { Text(stringResource(if (resource == null) R.string.new_event else R.string.edit_event)) },
                actions = {
                    if (busy) CircularProgressIndicator(Modifier.padding(end = 16.dp).size(22.dp), strokeWidth = 2.dp)
                    else TextButton(onClick = ::submit, enabled = calendarUrl.isNotEmpty()) { Text(stringResource(R.string.save), fontWeight = FontWeight.SemiBold) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.field_title)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (source?.isRecurring == true || scopeNow != Scope.ALL) {
                Text(stringResource(when (scopeNow) {
                    Scope.THIS -> R.string.editing_this
                    Scope.FOLLOWING -> R.string.editing_following
                    Scope.ALL -> R.string.editing_series
                }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Line(stringResource(R.string.all_day), onClick = { allDay = !allDay }) { Switch(checked = allDay, onCheckedChange = { allDay = it }) }
            DateTimeLine(stringResource(R.string.starts), start, allDay, onDate = { picking = "start-date" }, onTime = { picking = "start-time" })
            DateTimeLine(stringResource(R.string.ends), end, allDay, onDate = { picking = "end-date" }, onTime = { picking = "end-time" })
            if (zone != ZoneId.systemDefault() && !allDay) {
                Text(stringResource(R.string.in_zone, zone.id), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()

            val calendar = snapshot.calendar(calendarUrl)
            Choice(
                label = stringResource(R.string.field_calendar),
                value = calendar?.name ?: stringResource(R.string.no_calendars),
                dot = calendar?.let { Color(it.color) },
                options = if (scopeNow == Scope.ALL) writable.map { it.url to it.name } else emptyList(),
                onPick = { calendarUrl = it },
            )
            if (scopeNow != Scope.THIS) {
                val known = REPEATS.firstOrNull { it.first == rrule }
                Choice(
                    label = stringResource(R.string.field_repeat),
                    value = stringResource(known?.second ?: R.string.repeat_custom),
                    options = REPEATS.map { (it.first ?: "") to stringResource(it.second) },
                    onPick = { rrule = it.ifEmpty { null } },
                )
            }
            Choice(
                label = stringResource(R.string.field_reminder),
                value = if (alarms.isEmpty()) stringResource(R.string.reminder_none) else alarms.joinToString(", ") { reminderLabel(context, it) },
                options = listOf("" to stringResource(R.string.reminder_none)) + REMINDERS.map { it.toString() to reminderLabel(context, it) },
                onPick = { alarms = it.toIntOrNull()?.let(::listOf).orEmpty() },
            )
            HorizontalDivider()

            OutlinedTextField(
                value = location, onValueChange = { location = it }, label = { Text(stringResource(R.string.field_where)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            if (isOrganizer && scopeNow != Scope.THIS) {
                OutlinedTextField(
                    value = guests, onValueChange = { guests = it }, label = { Text(stringResource(R.string.field_guests)) },
                    supportingText = { Text(stringResource(R.string.guests_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email),
                )
            }
            OutlinedTextField(
                value = notes, onValueChange = { notes = it }, label = { Text(stringResource(R.string.field_notes)) },
                minLines = 3, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Spacer(Modifier.size(24.dp))
        }
    }

    when (picking) {
        "start-date" -> PickDate(start.toLocalDate(), onDismiss = { picking = null }) { setStart(it.atTime(start.toLocalTime())) }
        "end-date" -> PickDate(end.toLocalDate(), onDismiss = { picking = null }) {
            end = it.atTime(end.toLocalTime())
            if (end.isBefore(start)) start = if (allDay) end else end.minusHours(1)
        }
        "start-time" -> PickTime(start.toLocalTime(), onDismiss = { picking = null }) { setStart(start.toLocalDate().atTime(it)) }
        "end-time" -> PickTime(end.toLocalTime(), onDismiss = { picking = null }) {
            end = end.toLocalDate().atTime(it)
            // An end time earlier than the start on the same day means "the next day".
            if (end.isBefore(start)) end = end.plusDays(1)
        }
    }
}

@Composable
private fun Line(label: String, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        trailing()
    }
}

@Composable
private fun DateTimeLine(label: String, value: LocalDateTime, allDay: Boolean, onDate: () -> Unit, onTime: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onDate) { Text(Format.shortDate(value.toLocalDate())) }
        if (!allDay) TextButton(onClick = onTime) { Text(Format.time(context, value.toLocalTime())) }
    }
}

@Composable
private fun Choice(label: String, value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit, dot: Color? = null) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            if (dot != null) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(8.dp))
            }
            Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((key, text) in options) {
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    open = false
                    onPick(key)
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickDate(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    // The picker works in UTC milliseconds whatever the device zone.
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) { DatePicker(state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickTime(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(initial.hour, initial.minute, android.text.format.DateFormat.is24HourFormat(context))
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onPick(LocalTime.of(state.hour, state.minute))
                onDismiss()
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = { TimePicker(state) },
    )
}
