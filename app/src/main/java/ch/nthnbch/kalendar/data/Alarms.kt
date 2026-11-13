package ch.nthnbch.kalendar.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ch.nthnbch.kalendar.R
import ch.nthnbch.kalendar.ical.Occurrence
import ch.nthnbch.kalendar.ui.Format
import ch.nthnbch.kalendar.ui.Links
import ch.nthnbch.kalendar.widget.Widgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Two clocks driven by AlarmManager:
 * - a *tick* at the next moment the widgets would look different (an event starts or ends, midnight);
 * - a *reminder* at the next VALARM of an upcoming event.
 * Only the nearest of each is ever scheduled; firing one schedules the following.
 */
object Alarms {

    const val ACTION_TICK = "ch.nthnbch.kalendar.TICK"
    const val ACTION_REMINDER = "ch.nthnbch.kalendar.REMINDER"
    const val CHANNEL = "reminders"

    private data class Reminder(val at: Instant, val occurrence: Occurrence)

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH)
        manager.createNotificationChannel(channel)
    }

    fun reschedule(context: Context, snapshot: Snapshot) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val today = LocalDate.now(zone)

        // Widgets: next boundary among today's and tomorrow's events, at the latest next midnight.
        val midnight = today.plusDays(1).atStartOfDay(zone).toInstant()
        val boundary = snapshot.occurrences(today, today.plusDays(1), zone)
            .flatMap { listOf(it.startInstant, it.endInstant) }
            .filter { it.isAfter(now) && it.isBefore(midnight) }
            .minOrNull() ?: midnight
        // RTC without wake-up: nobody is looking at the home screen while the device sleeps.
        alarms.set(AlarmManager.RTC, boundary.toEpochMilli() + 500, pending(context, ACTION_TICK))

        val next = reminders(snapshot, zone).filter { it.at.isAfter(now) }.minByOrNull { it.at }
        val reminder = pending(context, ACTION_REMINDER)
        if (next == null) {
            alarms.cancel(reminder)
        } else {
            val at = next.at.toEpochMilli()
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, reminder)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, reminder)
        }
    }

    /** Posts the reminders that became due since the last time this ran. */
    fun fireDue(context: Context, snapshot: Snapshot) {
        val prefs = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)
        val now = Instant.now()
        // Never replay more than a few hours of missed reminders (device off, first run…).
        val floor = now.minus(6, ChronoUnit.HOURS).toEpochMilli()
        val last = maxOf(prefs.getLong("last_fired", now.toEpochMilli()), floor)
        prefs.edit().putLong("last_fired", now.toEpochMilli()).apply()

        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val manager = context.getSystemService(NotificationManager::class.java)
        val zone = ZoneId.systemDefault()
        reminders(snapshot, zone)
            .filter { it.at.toEpochMilli() in (last + 1)..now.toEpochMilli() && it.occurrence.endInstant.isAfter(now) }
            .distinctBy { it.occurrence.href to it.occurrence.startInstant }
            .forEach { reminder ->
                val o = reminder.occurrence
                val open = PendingIntent.getActivity(
                    context, 0, Links.event(context, o),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val notification = NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(o.event.summary.ifBlank { context.getString(R.string.untitled) })
                    .setContentText(Format.whenAndWhere(context, o, LocalDate.now(zone)))
                    .setColor(snapshot.calendar(o.calendarUrl)?.color ?: 0)
                    .setCategory(NotificationCompat.CATEGORY_EVENT)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setWhen(o.startInstant.toEpochMilli())
                    .setShowWhen(true)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build()
                manager.notify((o.href + o.startInstant.epochSecond).hashCode(), notification)
            }
    }

    private fun reminders(snapshot: Snapshot, zone: ZoneId): List<Reminder> {
        val today = LocalDate.now(zone)
        // Reminders can be set days ahead: look far enough for a "1 week before".
        return snapshot.occurrences(today.minusDays(1), today.plusDays(29), zone).flatMap { o ->
            o.event.alarms.map { minutes -> Reminder(o.startInstant.minus(minutes.toLong(), ChronoUnit.MINUTES), o) }
        }
    }

    private fun pending(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, TickReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Wakes up for ticks, reminders, reboots and clock changes; always leaves the next alarm scheduled. */
class TickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                val snapshot = Repository.get(app).ensureLoaded()
                Alarms.fireDue(app, snapshot)
                Alarms.reschedule(app, snapshot)
                Widgets.refresh(app)
            } finally {
                result.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
