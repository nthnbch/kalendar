package ch.nthnbch.kalendar.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
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
import ch.nthnbch.kalendar.ical.Occurrence
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

/** One event as written in a day cell: a short colored label. */
private data class Chip(val title: String, val color: Int)

/** Everything the layout needs, computed once per session off the main thread so that drawing is only layout. */
private class MonthModel(val month: YearMonth, val shifted: Boolean, val start: LocalDate, val weeks: Int, val chips: Map<LocalDate, List<Chip>>)

class MonthWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = Repository.get(context)
        val snapshot = repository.ensureLoaded()
        val prefs = getAppWidgetState<Preferences>(context, PreferencesGlanceStateDefinition, id)
        val today = LocalDate.now()
        val current = YearMonth.from(today)
        val offset = if (prefs[BASE] == index(current)) prefs[OFFSET] ?: 0 else 0
        val model = withContext(Dispatchers.Default) { model(snapshot, current.plusMonths(offset.toLong()), offset != 0) }
        provideContent {
            // Redrawn from scratch by every update() (data change, month shift, new day): nothing to observe here.
            GlanceTheme { Month(model, today) }
        }
    }

    private fun model(snapshot: Snapshot, month: YearMonth, shifted: Boolean): MonthModel {
        val start = Format.gridStart(month)
        val weeks = Format.gridWeeks(month)
        val end = start.plusDays(weeks * 7L)
        val byDay = HashMap<LocalDate, MutableList<Pair<Occurrence, Int>>>()
        for (o in snapshot.occurrences(start, end)) {
            val color = o.event.color ?: snapshot.calendar(o.calendarUrl)?.color ?: continue
            var day = maxOf(o.firstDay, start)
            val last = minOf(o.lastDay, end.minusDays(1))
            while (!day.isAfter(last)) {
                byDay.getOrPut(day) { ArrayList() } += o to color
                day = day.plusDays(1)
            }
        }
        // All-day events first, then by start time: the order of the agenda.
        val chips = byDay.mapValues { (_, list) ->
            list.sortedWith(compareBy({ !it.first.allDay }, { it.first.start }))
                .map { (o, color) -> Chip(o.event.summary.ifBlank { "·" }, color) }
        }
        return MonthModel(month, shifted, start, weeks, chips)
    }

    @Composable
    private fun Month(model: MonthModel, today: LocalDate) {
        val context = LocalContext.current
        val month = model.month
        // Room left for event labels in a cell: height of the grid / weeks, minus the day number.
        val cellHeight = (LocalSize.current.height.value - 8 - 10 - 34 - 15) / model.weeks
        val lines = ((cellHeight - 22) / 12).toInt().coerceIn(0, 4)

        Column(Widgets.surface().fillMaxSize().padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)) {
            Row(GlanceModifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Format.month(month, withYear = month.year != today.year),
                    style = TextStyle(
                        color = if (model.shifted) GlanceTheme.colors.onSurface else GlanceTheme.colors.primary,
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight().clickable(
                        // Tapping the title comes back to today when browsing, opens the app otherwise.
                        if (model.shifted) actionRunCallback<ShiftMonthAction>(actionParametersOf(DELTA to 0))
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

            for (week in 0 until model.weeks) {
                Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    for (d in 0 until 7) {
                        val day = model.start.plusDays((week * 7 + d).toLong())
                        DayCell(day, today, YearMonth.from(day) == month, model.chips[day].orEmpty(), lines, GlanceModifier.defaultWeight())
                    }
                }
            }
        }
    }

    @Composable
    private fun DayCell(day: LocalDate, today: LocalDate, inMonth: Boolean, chips: List<Chip>, lines: Int, modifier: GlanceModifier) {
        val context = LocalContext.current
        val isToday = day == today
        val alpha = if (inMonth) 1f else 0.45f
        Column(
            modifier.fillMaxHeight().padding(horizontal = 1.dp).clickable(actionStartActivity(Links.day(context, day))),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                GlanceModifier.size(22.dp)
                    .then(if (isToday) GlanceModifier.background(GlanceTheme.colors.primary).cornerRadius(11.dp) else GlanceModifier),
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
                        fontSize = 12.sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
            if (lines == 0) {
                // Too small for labels: colored dots are all that fits.
                Row(GlanceModifier.height(6.dp).padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    chips.map { it.color }.distinct().take(3).forEachIndexed { i, color ->
                        if (i > 0) Spacer(GlanceModifier.width(2.dp))
                        Box(GlanceModifier.size(5.dp).background(Widgets.tint(color, alpha)).cornerRadius(3.dp)) {}
                    }
                }
            } else {
                val overflow = chips.size > lines
                val shown = if (overflow) chips.take(lines - 1) else chips
                for (chip in shown) {
                    Box(GlanceModifier.fillMaxWidth().padding(top = 1.dp)) {
                        Text(
                            text = chip.title,
                            maxLines = 1,
                            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 9.sp, fontWeight = FontWeight.Medium),
                            modifier = GlanceModifier.fillMaxWidth()
                                .background(Widgets.tint(chip.color, if (inMonth) 0.30f else 0.12f))
                                .cornerRadius(3.dp).padding(horizontal = 2.dp),
                        )
                    }
                }
                if (overflow) {
                    Text(
                        text = "+" + (chips.size - shown.size),
                        maxLines = 1,
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 9.sp),
                    )
                }
            }
        }
    }
}
