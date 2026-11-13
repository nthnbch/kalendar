package ch.nthnbch.kalendar.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
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
import ch.nthnbch.kalendar.ui.Format
import ch.nthnbch.kalendar.ui.Links
import java.time.LocalDate
import java.time.YearMonth

class MonthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MonthWidget()
}

/** The month the widget shows is `BASE + OFFSET`; BASE remembers which month the offset was chosen in. */
private val OFFSET = intPreferencesKey("offset")
private val BASE = intPreferencesKey("base")
private val DELTA = ActionParameters.Key<Int>("delta")

private fun index(month: YearMonth): Int = month.year * 12 + month.monthValue - 1

class ShiftMonthAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val delta = parameters[DELTA] ?: 0
        val current = index(YearMonth.now())
        updateAppWidgetState(context, glanceId) { prefs ->
            // An offset chosen during a previous month is stale: start again from the current one.
            val offset = if (prefs[BASE] == current) prefs[OFFSET] ?: 0 else 0
            prefs[BASE] = current
            prefs[OFFSET] = if (delta == 0) 0 else offset + delta
        }
        MonthWidget().update(context, glanceId)
    }
}

class MonthWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = Repository.get(context)
        repository.ensureLoaded()
        provideContent {
            val snapshot by repository.state.collectAsState()
            val tick by Widgets.tick.collectAsState()
            val prefs = currentState<Preferences>()
            GlanceTheme {
                val today = remember(tick) { LocalDate.now() }
                val current = YearMonth.from(today)
                val offset = if (prefs[BASE] == index(current)) prefs[OFFSET] ?: 0 else 0
                Month(snapshot, today, current.plusMonths(offset.toLong()), offset != 0)
            }
        }
    }

    @Composable
    private fun Month(snapshot: Snapshot, today: LocalDate, month: YearMonth, shifted: Boolean) {
        val context = LocalContext.current
        val start = Format.gridStart(month)
        val weeks = Format.gridWeeks(month)
        val dots = remember(snapshot, start, weeks) { dots(snapshot, start, weeks) }

        Column(Widgets.surface().fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 10.dp)) {
            Row(GlanceModifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Format.month(month, withYear = month.year != today.year),
                    style = TextStyle(
                        color = if (shifted) GlanceTheme.colors.onSurface else GlanceTheme.colors.primary,
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight().clickable(
                        // Tapping the title comes back to today when browsing, opens the app otherwise.
                        if (shifted) actionRunCallback<ShiftMonthAction>(actionParametersOf(DELTA to 0))
                        else actionStartActivity(Links.day(context, today)),
                    ),
                )
                IconButton(R.drawable.ic_chevron_left, R.string.previous_month, false, actionRunCallback<ShiftMonthAction>(actionParametersOf(DELTA to -1)))
                IconButton(R.drawable.ic_chevron_right, R.string.next_month, false, actionRunCallback<ShiftMonthAction>(actionParametersOf(DELTA to 1)))
            }

            Row(GlanceModifier.fillMaxWidth().padding(bottom = 2.dp)) {
                for (weekday in Format.weekdays()) {
                    Text(
                        text = Format.weekdayNarrow(weekday),
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
                        modifier = GlanceModifier.defaultWeight(),
                    )
                }
            }

            for (week in 0 until weeks) {
                Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    for (d in 0 until 7) {
                        val day = start.plusDays((week * 7 + d).toLong())
                        DayCell(day, today, inMonth = YearMonth.from(day) == month, colors = dots[day].orEmpty(), modifier = GlanceModifier.defaultWeight())
                    }
                }
            }
        }
    }

    @Composable
    private fun DayCell(day: LocalDate, today: LocalDate, inMonth: Boolean, colors: List<Int>, modifier: GlanceModifier) {
        val context = LocalContext.current
        val isToday = day == today
        Column(
            modifier.fillMaxHeight().clickable(actionStartActivity(Links.day(context, day))),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                GlanceModifier.size(26.dp)
                    .then(if (isToday) GlanceModifier.background(GlanceTheme.colors.primary).cornerRadius(13.dp) else GlanceModifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = day.dayOfMonth.toString(),
                    style = TextStyle(
                        color = when {
                            isToday -> GlanceTheme.colors.onPrimary
                            inMonth -> GlanceTheme.colors.onSurface
                            else -> GlanceTheme.colors.outline
                        },
                        fontSize = 13.sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
            // The dot row always takes its height so numbers stay aligned across cells.
            Row(GlanceModifier.height(6.dp), verticalAlignment = Alignment.CenterVertically) {
                colors.take(3).forEachIndexed { i, color ->
                    if (i > 0) Spacer(GlanceModifier.width(2.dp))
                    Box(GlanceModifier.size(5.dp).background(Widgets.tint(color, if (inMonth) 1f else 0.4f)).cornerRadius(3.dp)) {}
                }
            }
        }
    }

    private fun dots(snapshot: Snapshot, start: LocalDate, weeks: Int): Map<LocalDate, List<Int>> {
        val end = start.plusDays(weeks * 7L)
        val out = HashMap<LocalDate, MutableList<Int>>()
        for (o in snapshot.occurrences(start, end)) {
            val color = o.event.color ?: snapshot.calendar(o.calendarUrl)?.color ?: continue
            var day = maxOf(o.firstDay, start)
            val last = minOf(o.lastDay, end.minusDays(1))
            while (!day.isAfter(last)) {
                val list = out.getOrPut(day) { ArrayList() }
                if (color !in list) list += color
                day = day.plusDays(1)
            }
        }
        return out
    }
}
