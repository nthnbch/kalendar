package ch.nthnbch.kalendar.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import ch.nthnbch.kalendar.R
import ch.nthnbch.kalendar.data.Repository
import ch.nthnbch.kalendar.data.Snapshot
import ch.nthnbch.kalendar.ical.Occurrence
import ch.nthnbch.kalendar.ui.Format
import ch.nthnbch.kalendar.ui.Links
import ch.nthnbch.kalendar.ui.MainActivity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class AgendaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AgendaWidget()
}

/** One line of the agenda. [showDay] is set on the first line of each day, which carries the date gutter. */
private data class AgendaRow(
    val id: Long,
    val day: LocalDate,
    val showDay: Boolean,
    val occurrence: Occurrence?,
    val color: Int,
    val ongoing: Boolean,
)

class AgendaWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = Repository.get(context)
        repository.ensureLoaded()
        provideContent {
            val snapshot by repository.state.collectAsState()
            val account by repository.account.collectAsState()
            val syncing by repository.syncing.collectAsState()
            val tick by Widgets.tick.collectAsState()
            GlanceTheme {
                if (account == null) SignedOut() else Agenda(snapshot, syncing, tick)
            }
        }
    }

    @Composable
    private fun Agenda(snapshot: Snapshot, syncing: Boolean, tick: Long) {
        val context = LocalContext.current
        val today = remember(tick) { LocalDate.now() }
        val rows = remember(snapshot, tick) { rows(snapshot, today) }
        val size = LocalSize.current
        val roomy = size.width >= 200.dp

        Column(Widgets.surface().fillMaxSize()) {
            Row(
                GlanceModifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(GlanceModifier.defaultWeight().clickable(actionStartActivity(Links.day(context, today)))) {
                    Text(
                        text = if (syncing) context.getString(R.string.syncing) else Format.weekday(today),
                        style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                    )
                    Text(
                        text = Format.dayAndMonth(today),
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                }
                if (roomy) {
                    IconButton(R.drawable.ic_refresh, R.string.sync_now, filled = false, onClick = actionRunCallback<RefreshAction>())
                    Spacer(GlanceModifier.width(4.dp))
                }
                IconButton(R.drawable.ic_add, R.string.new_event, filled = true, onClick = actionStartActivity(Links.new(context, today)))
            }

            LazyColumn(GlanceModifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(rows, itemId = { it.id }) { row -> AgendaLine(row, today) }
                item { Spacer(GlanceModifier.height(10.dp)) }
            }
        }
    }

    @Composable
    private fun AgendaLine(row: AgendaRow, today: LocalDate) {
        val context = LocalContext.current
        // Extra air above a new day groups the lines visually without needing separators.
        val top = if (row.showDay && row.day != today) 10.dp else 2.dp
        Row(GlanceModifier.fillMaxWidth().padding(top = top, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            DayGutter(row, today)
            val occurrence = row.occurrence
            if (occurrence == null) {
                Text(
                    text = context.getString(R.string.nothing_planned),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                    modifier = GlanceModifier.defaultWeight().padding(horizontal = 12.dp, vertical = 12.dp)
                        .clickable(actionStartActivity(Links.new(context, row.day))),
                )
            } else {
                Row(
                    GlanceModifier.defaultWeight()
                        .background(Widgets.tint(row.color, if (row.ongoing) 0.30f else 0.16f))
                        .cornerRadius(12.dp)
                        .padding(horizontal = 8.dp, vertical = 7.dp)
                        .clickable(actionStartActivity(Links.event(context, occurrence))),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(GlanceModifier.width(4.dp).height(32.dp).background(Widgets.solid(row.color)).cornerRadius(2.dp)) {}
                    Spacer(GlanceModifier.width(8.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        Text(
                            text = occurrence.event.summary.ifBlank { context.getString(R.string.untitled) },
                            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                            maxLines = 1,
                        )
                        Text(
                            text = (if (row.ongoing) context.getString(R.string.now) + " · " else "") +
                                Format.whenAndWhere(context, occurrence, row.day),
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun DayGutter(row: AgendaRow, today: LocalDate) {
        val context = LocalContext.current
        Column(
            GlanceModifier.width(44.dp).clickable(actionStartActivity(Links.day(context, row.day))),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!row.showDay) return@Column
            val isToday = row.day == today
            Text(
                text = Format.weekdayShort(row.day.dayOfWeek).uppercase(),
                style = TextStyle(
                    color = if (isToday) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 10.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                ),
                maxLines = 1,
            )
            Box(
                GlanceModifier.size(28.dp)
                    .then(if (isToday) GlanceModifier.background(GlanceTheme.colors.primary).cornerRadius(14.dp) else GlanceModifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = row.day.dayOfMonth.toString(),
                    style = TextStyle(
                        color = if (isToday) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSurface,
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }

    @Composable
    private fun SignedOut() {
        val context = LocalContext.current
        Column(
            Widgets.surface().fillMaxSize().padding(16.dp)
                .clickable(actionStartActivity(android.content.Intent(context, MainActivity::class.java))),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = context.getString(R.string.app_name),
                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.height(4.dp))
            Text(
                text = context.getString(R.string.widget_sign_in),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.Center),
            )
        }
    }

    companion object {
        private const val DAYS_AHEAD = 30L
        private const val MAX_ROWS = 60

        private fun rows(snapshot: Snapshot, today: LocalDate): List<AgendaRow> {
            val zone = ZoneId.systemDefault()
            val now = Instant.now()
            val last = today.plusDays(DAYS_AHEAD)
            val occurrences = snapshot.occurrences(today, last, zone)
            val out = ArrayList<AgendaRow>()

            var day = today
            while (day.isBefore(last) && out.size < MAX_ROWS) {
                val current = day
                val ofDay = occurrences
                    .filter { it.covers(current) }
                    // What is over is noise: on a widget only the rest of the day matters.
                    .filter { it.allDay || it.endInstant.isAfter(now) || (it.end == it.start && it.startInstant.isAfter(now)) }
                    .sortedWith(compareBy<Occurrence> { !(it.allDay || it.firstDay.isBefore(current)) }.thenBy { it.start })
                if (ofDay.isEmpty() && current == today) {
                    out += AgendaRow(current.toEpochDay() shl 20, current, true, null, 0, false)
                }
                ofDay.forEachIndexed { index, o ->
                    out += AgendaRow(
                        // Stable across refreshes so the list keeps its scroll position.
                        id = (current.toEpochDay() shl 20) or ((o.href.hashCode() xor o.startInstant.epochSecond.toInt()).toLong() and 0xFFFFF),
                        day = current,
                        showDay = index == 0,
                        occurrence = o,
                        color = o.event.color ?: snapshot.calendar(o.calendarUrl)?.color ?: 0xFF0098FF.toInt(),
                        ongoing = !o.allDay && current == today && !o.startInstant.isAfter(now) && o.endInstant.isAfter(now),
                    )
                }
                day = day.plusDays(1)
            }
            return out.distinctBy { it.id }
        }
    }
}

/** 40dp round button; filled for the primary action, bare for secondary ones. */
@Composable
internal fun IconButton(icon: Int, description: Int, filled: Boolean, onClick: androidx.glance.action.Action) {
    val context = LocalContext.current
    Box(
        GlanceModifier.size(40.dp)
            .then(if (filled) GlanceModifier.background(GlanceTheme.colors.primary) else GlanceModifier)
            .cornerRadius(20.dp)
            .clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = context.getString(description),
            colorFilter = ColorFilter.tint(if (filled) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSurfaceVariant),
            modifier = GlanceModifier.size(22.dp),
        )
    }
}
