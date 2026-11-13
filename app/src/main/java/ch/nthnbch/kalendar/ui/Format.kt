package ch.nthnbch.kalendar.ui

import android.content.Context
import android.text.format.DateFormat
import ch.nthnbch.kalendar.R
import ch.nthnbch.kalendar.ical.Occurrence
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Date and time wording shared by the screens, the widgets and the notifications. */
object Format {

    private fun locale(): Locale = Locale.getDefault()

    fun time(context: Context, time: LocalTime): String {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        return time.format(DateTimeFormatter.ofPattern(pattern, locale()))
    }

    fun time(context: Context, moment: ZonedDateTime): String = time(context, moment.toLocalTime())

    /** How the occurrence reads on [day], which matters for events spanning several days. */
    fun timeRange(context: Context, o: Occurrence, day: LocalDate): String {
        if (o.allDay) return context.getString(R.string.all_day)
        val startsToday = o.firstDay == day
        val endsToday = o.lastDay == day
        return when {
            startsToday && endsToday ->
                if (o.end == o.start) time(context, o.start) else "${time(context, o.start)} – ${time(context, o.end)}"
            startsToday -> context.getString(R.string.from_time, time(context, o.start))
            endsToday -> context.getString(R.string.until_time, time(context, o.end))
            else -> context.getString(R.string.all_day)
        }
    }

    fun whenAndWhere(context: Context, o: Occurrence, day: LocalDate): String {
        val range = timeRange(context, o, day)
        return if (o.event.location.isBlank()) range else "$range · ${o.event.location.lineSequence().first()}"
    }

    fun dayTitle(context: Context, date: LocalDate, today: LocalDate): String = when (date) {
        today -> context.getString(R.string.today)
        today.plusDays(1) -> context.getString(R.string.tomorrow)
        today.minusDays(1) -> context.getString(R.string.yesterday)
        else -> fullDate(date, withYear = date.year != today.year)
    }

    fun fullDate(date: LocalDate, withYear: Boolean = true): String {
        val skeleton = if (withYear) "EEEEdMMMMyyyy" else "EEEEdMMMM"
        val pattern = DateFormat.getBestDateTimePattern(locale(), skeleton)
        return date.format(DateTimeFormatter.ofPattern(pattern, locale())).capitalized()
    }

    fun shortDate(date: LocalDate): String {
        val pattern = DateFormat.getBestDateTimePattern(locale(), "EEEdMMMyyyy")
        return date.format(DateTimeFormatter.ofPattern(pattern, locale())).capitalized()
    }

    fun dayAndMonth(date: LocalDate): String {
        val pattern = DateFormat.getBestDateTimePattern(locale(), "dMMMM")
        return date.format(DateTimeFormatter.ofPattern(pattern, locale()))
    }

    fun month(month: YearMonth, withYear: Boolean = true): String {
        val pattern = if (withYear) "LLLL yyyy" else "LLLL"
        return month.atDay(1).format(DateTimeFormatter.ofPattern(pattern, locale())).capitalized()
    }

    fun weekday(date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.FULL, locale()).capitalized()

    fun weekdayShort(day: DayOfWeek): String =
        day.getDisplayName(TextStyle.SHORT, locale()).removeSuffix(".").capitalized()

    fun weekdayNarrow(day: DayOfWeek): String = day.getDisplayName(TextStyle.NARROW, locale()).uppercase(locale())

    /** The seven days in the order the user's region starts its week. */
    fun weekdays(): List<DayOfWeek> {
        val first = WeekFields.of(locale()).firstDayOfWeek
        return (0L..6L).map { first.plus(it) }
    }

    /** First cell of a month grid: the start of the week containing the 1st. */
    fun gridStart(month: YearMonth): LocalDate {
        val first = month.atDay(1)
        val shift = (first.dayOfWeek.value - WeekFields.of(locale()).firstDayOfWeek.value + 7) % 7
        return first.minusDays(shift.toLong())
    }

    /** Number of week rows needed to show the whole month. */
    fun gridWeeks(month: YearMonth): Int {
        val start = gridStart(month)
        val days = java.time.temporal.ChronoUnit.DAYS.between(start, month.atEndOfMonth()) + 1
        return ((days + 6) / 7).toInt()
    }

    private fun String.capitalized(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale()) else it.toString() }
}
