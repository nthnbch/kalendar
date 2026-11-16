package ch.nthnbch.kalendar.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.state.PreferencesGlanceStateDefinition
import kotlinx.coroutines.delay
import androidx.datastore.preferences.core.intPreferencesKey
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.layout.ContentScale
import androidx.glance.unit.ColorProvider
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
        // Taps in quick succession only need one redraw, of the last month: a render costs a few hundred ms,
        // and one per tap would queue up behind the user's finger.
        val mine = taps.incrementAndGet()
        delay(180)
        if (taps.get() == mine) MonthWidget().update(context, glanceId)
    }

    private companion object {
        val taps = java.util.concurrent.atomic.AtomicInteger()
    }
}

/** One event as written in a day cell: a short colored label. */
private data class Chip(val title: String, val color: Int)

/** Everything the layout needs, computed once per session off the main thread so that drawing is only layout. */
private class MonthModel(val month: YearMonth, val shifted: Boolean, val start: LocalDate, val weeks: Int, val chips: Map<LocalDate, List<Chip>>)

class MonthWidget : GlanceAppWidget() {

    private companion object {
        val cache = LinkedHashMap<YearMonth, MonthModel>()
        var cacheSnapshot: Snapshot? = null
    }

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = Repository.get(context)
        repository.ensureLoaded()
        // The session stays alive between taps and update() only recomposes it: everything that can change
        // (month offset, data, the clock) has to be read inside the composition, not computed here once.
        provideContent {
            val snapshot by repository.state.collectAsState()
            val tick by Widgets.tick.collectAsState()
            val prefs = currentState<Preferences>()
            val today = remember(tick, prefs) { LocalDate.now() }
            val current = YearMonth.from(today)
            val offset = if (prefs[BASE] == index(current)) prefs[OFFSET] ?: 0 else 0
            val model = remember(snapshot, current, offset) { cachedModel(snapshot, current.plusMonths(offset.toLong()), offset != 0) }
            GlanceTheme { Month(model, today) }
        }
    }

    /** Browsing back and forth revisits the same months: keep the last few models instead of expanding every event again. */
    private fun cachedModel(snapshot: Snapshot, month: YearMonth, shifted: Boolean): MonthModel {
        synchronized(cache) {
            if (cacheSnapshot !== snapshot) { cache.clear(); cacheSnapshot = snapshot }
            cache[month]?.let { return MonthModel(it.month, shifted, it.start, it.weeks, it.chips) }
        }
        val built = model(snapshot, month, shifted)
        synchronized(cache) {
            if (cacheSnapshot === snapshot) {
                if (cache.size >= 6) cache.remove(cache.keys.first())
                cache[month] = built
            }
        }
        return built
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
        val lines = ((cellHeight - 22) / 12).toInt().coerceIn(0, 3)

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

            // The whole grid is one picture (like Fossify Calendar does): a few hundred views made every
            // month change slow. Invisible clickable cells on top keep each day tappable.
            val size = LocalSize.current
            val density = context.resources.displayMetrics.density
            val gridW = size.width.value - 12
            val gridH = size.height.value - 16 - 40
            val onSurface = GlanceTheme.colors.onSurface.getColor(context).toArgb()
            val colors = GridColors(
                onSurface, GlanceTheme.colors.onSurfaceVariant.getColor(context).toArgb(), GlanceTheme.colors.outline.getColor(context).toArgb(),
                GlanceTheme.colors.primary.getColor(context).toArgb(), GlanceTheme.colors.onPrimary.getColor(context).toArgb(),
            )
            // The system counts the picture several times against a ~15 MB budget: keep it under ~3 MB.
            val scale = minOf(1f, Math.sqrt(3_000_000.0 / (gridW * density * gridH * density * 4.0)).toFloat())
            val bitmap = remember(model, today, gridW, gridH, colors) { drawGrid(model, today, (gridW * density * scale).toInt(), (gridH * density * scale).toInt(), density * scale, colors) }
            Box(GlanceModifier.fillMaxWidth().defaultWeight()) {
                Image(ImageProvider(bitmap), contentDescription = null, modifier = GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                Column(GlanceModifier.fillMaxSize()) {
                    Spacer(GlanceModifier.height(WEEKDAYS_DP.dp))
                    for (week in 0 until model.weeks) {
                        Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                            for (d in 0 until 7) {
                                val day = model.start.plusDays((week * 7 + d).toLong())
                                Box(GlanceModifier.defaultWeight().fillMaxHeight().clickable(actionStartActivity(Links.day(context, day)))) {}
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val WEEKDAYS_DP = 20

private data class GridColors(val onSurface: Int, val onSurfaceVariant: Int, val outline: Int, val primary: Int, val onPrimary: Int)

private fun drawGrid(model: MonthModel, today: LocalDate, w: Int, h: Int, density: Float, c: GridColors): Bitmap {
    val bitmap = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    fun dp(v: Float) = v * density
    val cellW = w / 7f
    val top = dp(WEEKDAYS_DP.toFloat())
    val cellH = (h - top) / model.weeks

    text.color = c.onSurfaceVariant; text.textSize = dp(11f); text.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    Format.weekdays().forEachIndexed { i, wd ->
        canvas.drawText(Format.weekdayNarrow(wd), cellW * (i + 0.5f), dp(13f), text)
    }

    val lines = ((cellH / density - 22) / 12).toInt().coerceIn(0, 5)
    for (week in 0 until model.weeks) {
        for (d in 0 until 7) {
            val day = model.start.plusDays((week * 7 + d).toLong())
            val inMonth = YearMonth.from(day) == model.month
            val isToday = day == today
            val x = cellW * d
            val y = top + cellH * week
            val cx = x + cellW / 2
            val numberY = y + dp(16f)
            if (isToday) {
                paint.color = c.primary
                canvas.drawCircle(cx, y + dp(11f), dp(11f), paint)
            }
            text.textSize = dp(12f)
            text.typeface = Typeface.create(Typeface.DEFAULT, if (isToday) 700 else 500, false)
            text.color = when { isToday -> c.onPrimary; inMonth -> c.onSurface; else -> c.outline }
            canvas.drawText(day.dayOfMonth.toString(), cx, numberY, text)

            val chips = model.chips[day].orEmpty()
            val pad = dp(1f)
            var cy = y + dp(24f)
            if (lines == 0) {
                chips.map { it.color }.distinct().take(3).forEachIndexed { i, color ->
                    paint.color = tinted(color, if (inMonth) 1f else 0.45f)
                    canvas.drawCircle(cx + (i - 1) * dp(7f), cy + dp(3f), dp(2.5f), paint)
                }
                continue
            }
            val overflow = chips.size > lines
            val shown = if (overflow) chips.take(lines - 1) else chips
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(9f); color = c.onSurface; typeface = Typeface.create(Typeface.DEFAULT, 500, false) }
            for (chip in shown) {
                val l = x + pad; val r = x + cellW - pad
                paint.color = tinted(chip.color, if (inMonth) 0.30f else 0.12f)
                canvas.drawRoundRect(l, cy, r, cy + dp(11f), dp(3f), dp(3f), paint)
                val fitted = TextUtils.ellipsize(chip.title, TextPaint(label), r - l - dp(4f), TextUtils.TruncateAt.END)
                canvas.drawText(fitted, 0, fitted.length, l + dp(2f), cy + dp(8.5f), label)
                cy += dp(12f)
            }
            if (overflow) {
                label.color = c.onSurfaceVariant; label.textAlign = Paint.Align.CENTER
                canvas.drawText("+" + (chips.size - shown.size), cx, cy + dp(8.5f), label)
            }
        }
    }
    return bitmap
}

private fun tinted(color: Int, alpha: Float): Int = (color and 0xFFFFFF) or ((alpha * 255).toInt() shl 24)
