package ch.nthnbch.kalendar.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.nthnbch.kalendar.R
import ch.nthnbch.kalendar.data.Repository
import ch.nthnbch.kalendar.data.Snapshot
import ch.nthnbch.kalendar.data.SyncWorker
import ch.nthnbch.kalendar.ical.Occurrence
import ch.nthnbch.kalendar.ical.Scope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailSheet(
    snapshot: Snapshot,
    occurrence: Occurrence,
    onDismiss: () -> Unit,
    myEmail: String?,
    onEdit: (Scope) -> Unit,
    onRespond: (String) -> Unit,
    onDelete: (Scope) -> Unit,
) {
    val context = LocalContext.current
    val event = occurrence.event
    val calendar = snapshot.calendar(occurrence.calendarUrl)
    val resource = snapshot.resource(occurrence.href)
    val recurring = event.recurrenceKey != null || resource?.events?.any { it.isRecurring } == true
    var confirmDelete by remember { mutableStateOf(false) }
    var chooseEdit by remember { mutableStateOf(false) }
    val mine = myEmail?.let { me -> event.attendees.firstOrNull { it.email.equals(me, ignoreCase = true) } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 8.dp).size(14.dp).clip(CircleShape).background(snapshot.colorOf(occurrence)))
                Spacer(Modifier.width(12.dp))
                Text(event.summary.ifBlank { stringResource(R.string.untitled) }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }

            Field(stringResource(R.string.field_when), whenText(context, occurrence) + if (recurring) "\n" + stringResource(R.string.repeats) else "")
            if (event.location.isNotBlank()) {
                Field(stringResource(R.string.field_where), event.location, onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(event.location)))) }
                })
            }
            calendar?.let { Field(stringResource(R.string.field_calendar), it.name) }
            if (event.alarms.isNotEmpty()) {
                Field(stringResource(R.string.field_reminder), event.alarms.joinToString(", ") { reminderLabel(context, it) })
            }
            if (event.attendees.isNotEmpty()) {
                val organizer = event.organizer?.let { stringResource(R.string.organized_by, it) }
                Field(
                    stringResource(R.string.field_guests),
                    (listOfNotNull(organizer) + event.attendees.map { "${statusMark(it.status)} ${it.name}" }).joinToString("\n"),
                )
            }
            if (mine != null && calendar != null && !calendar.readOnly) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.your_reply), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((status, label) in listOf(
                            "ACCEPTED" to R.string.reply_yes, "TENTATIVE" to R.string.reply_maybe, "DECLINED" to R.string.reply_no,
                        )) {
                            val selected = mine.status.equals(status, ignoreCase = true)
                            if (selected) Button(onClick = {}, modifier = Modifier.weight(1f)) { Text(stringResource(label)) }
                            else OutlinedButton(onClick = { onRespond(status) }, modifier = Modifier.weight(1f)) { Text(stringResource(label)) }
                        }
                    }
                }
            }
            if (event.description.isNotBlank()) Field(stringResource(R.string.field_notes), event.description)
            event.url?.let { url ->
                Field(stringResource(R.string.field_link), url, onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                })
            }

            if (calendar != null && !calendar.readOnly) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                    Button(onClick = { if (recurring) chooseEdit = true else onEdit(Scope.ALL) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.edit))
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        DeleteDialog(
            title = event.summary.ifBlank { stringResource(R.string.untitled) }, recurring = recurring,
            onDelete = { confirmDelete = false; onDelete(it) }, onCancel = { confirmDelete = false },
        )
    }

    if (chooseEdit) EditScopeDialog(onPick = { chooseEdit = false; onEdit(it) }, onCancel = { chooseEdit = false })
}

fun isRecurring(snapshot: Snapshot, occurrence: Occurrence): Boolean =
    occurrence.event.recurrenceKey != null || snapshot.resource(occurrence.href)?.events?.any { it.isRecurring } == true

@Composable
fun DeleteDialog(title: String, recurring: Boolean, onDelete: (Scope) -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.delete_title)) },
        text = { Text(title) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (recurring) {
                    TextButton(onClick = { onDelete(Scope.THIS) }) { Text(stringResource(R.string.delete_this_one)) }
                    TextButton(onClick = { onDelete(Scope.FOLLOWING) }) { Text(stringResource(R.string.scope_following)) }
                    TextButton(onClick = { onDelete(Scope.ALL) }) { Text(stringResource(R.string.delete_series), color = MaterialTheme.colorScheme.error) }
                } else {
                    TextButton(onClick = { onDelete(Scope.ALL) }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun EditScopeDialog(onPick: (Scope) -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.edit_which)) },
        text = {
            Column {
                TextButton(onClick = { onPick(Scope.THIS) }) { Text(stringResource(R.string.delete_this_one)) }
                TextButton(onClick = { onPick(Scope.FOLLOWING) }) { Text(stringResource(R.string.scope_following)) }
                TextButton(onClick = { onPick(Scope.ALL) }) { Text(stringResource(R.string.delete_series)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun statusMark(status: String?): String = when (status?.uppercase()) {
    "ACCEPTED" -> "✓"
    "DECLINED" -> "✗"
    "TENTATIVE" -> "?"
    else -> "·"
}

@Composable
private fun Field(label: String, value: String, onClick: (() -> Unit)? = null) {
    Column(if (onClick != null) Modifier.fillMaxWidth().clickable(onClick = onClick) else Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onClick != null) {
            Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        } else {
            SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

private fun whenText(context: Context, o: Occurrence): String {
    val first = o.firstDay
    val last = o.lastDay
    return when {
        o.allDay && first == last -> Format.fullDate(first)
        o.allDay -> "${Format.fullDate(first)} – ${Format.fullDate(last)}"
        first == last -> Format.fullDate(first) + "\n" + Format.timeRange(context, o, first)
        else -> "${Format.shortDate(first)} ${Format.time(context, o.start)} – ${Format.shortDate(o.end.toLocalDate())} ${Format.time(context, o.end)}"
    }
}

fun reminderLabel(context: Context, minutes: Int): String = when {
    minutes == 0 -> context.getString(R.string.reminder_at_start)
    minutes < 0 -> context.getString(R.string.reminder_after)
    minutes % 10080 == 0 -> context.resources.getQuantityString(R.plurals.weeks_before, minutes / 10080, minutes / 10080)
    minutes % 1440 == 0 -> context.resources.getQuantityString(R.plurals.days_before, minutes / 1440, minutes / 1440)
    minutes % 60 == 0 -> context.resources.getQuantityString(R.plurals.hours_before, minutes / 60, minutes / 60)
    else -> context.resources.getQuantityString(R.plurals.minutes_before, minutes, minutes)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarsSheet(repository: Repository, snapshot: Snapshot, onDismiss: () -> Unit, onSync: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account by repository.account.collectAsStateWithLifecycle()
    val syncing by repository.syncing.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(account?.label.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                val last = repository.lastSync
                Text(
                    when {
                        syncing -> stringResource(R.string.syncing)
                        last == 0L -> stringResource(R.string.never_synced)
                        else -> stringResource(R.string.last_sync, DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()

            for (calendar in snapshot.calendars) {
                val color = Color(calendar.color)
                Row(
                    Modifier.fillMaxWidth().clickable { scope.launch { repository.setVisible(calendar.url, !calendar.visible) } }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = calendar.visible,
                        onCheckedChange = { checked -> scope.launch { repository.setVisible(calendar.url, checked) } },
                        colors = CheckboxDefaults.colors(checkedColor = color, uncheckedColor = color),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(calendar.name, style = MaterialTheme.typography.bodyLarge)
                        if (calendar.readOnly) {
                            Text(stringResource(R.string.read_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (snapshot.calendars.isEmpty()) {
                Text(stringResource(R.string.no_calendars), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider()
            Text(
                stringResource(R.string.settings),
                Modifier.fillMaxWidth().clickable(onClick = onSettings).padding(horizontal = 24.dp, vertical = 14.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.sign_out)) }
                Button(onClick = onSync, enabled = !syncing, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.sync_now)) }
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.sign_out_title)) },
            text = { Text(stringResource(R.string.sign_out_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    SyncWorker.cancel(context)
                    scope.launch { repository.signOut() }
                }) { Text(stringResource(R.string.sign_out)) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
