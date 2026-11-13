package ch.nthnbch.kalendar.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ch.nthnbch.kalendar.R
import ch.nthnbch.kalendar.ical.EventParser
import ch.nthnbch.kalendar.ui.Links
import java.time.ZoneId

/**
 * "Someone answered your invitation". CalDAV has no push channel, so the answers are spotted when the event
 * is next downloaded: a guest whose reply differs from the copy we already had.
 */
object Replies {

    const val CHANNEL = "replies"

    private val ANSWERS = setOf("ACCEPTED", "DECLINED", "TENTATIVE")

    data class Reply(val href: String, val title: String, val who: String, val status: String, val startEpochSecond: Long)

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.channel_replies), NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** New answers to events [myEmail] organizes, found by comparing the stored copy with the downloaded one. */
    fun detect(href: String, oldIcs: String, newIcs: String, myEmail: String, zone: ZoneId): List<Reply> {
        val before = EventParser.parse(oldIcs, zone).associateBy { it.recurrenceKey }
        return EventParser.parse(newIcs, zone)
            .filter { it.organizerEmail.equals(myEmail, ignoreCase = true) }
            .flatMap { event ->
                val old = before[event.recurrenceKey] ?: return@flatMap emptyList()
                event.attendees.mapNotNull { guest ->
                    val status = guest.status?.uppercase()
                    val previous = old.attendees.firstOrNull { it.email.equals(guest.email, ignoreCase = true) }
                    if (guest.email.equals(myEmail, ignoreCase = true) || status !in ANSWERS || previous == null) return@mapNotNull null
                    if (previous.status.equals(status, ignoreCase = true)) return@mapNotNull null
                    Reply(href, event.summary, guest.name, status!!, event.start.atZone(event.zone ?: zone).toEpochSecond())
                }
            }
            .distinctBy { it.who to it.status }
    }

    fun notify(context: Context, replies: List<Reply>) {
        if (replies.isEmpty() || !Settings.notifyReplies(context)) return
        val granted = android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val manager = context.getSystemService(NotificationManager::class.java)
        for (reply in replies) {
            val text = context.getString(
                when (reply.status) {
                    "ACCEPTED" -> R.string.reply_notice_accepted
                    "DECLINED" -> R.string.reply_notice_declined
                    else -> R.string.reply_notice_tentative
                },
                reply.who,
            )
            val open = PendingIntent.getActivity(
                context, 0, Links.event(context, reply.href, reply.startEpochSecond),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(reply.title.ifBlank { context.getString(R.string.untitled) })
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            manager.notify("${reply.href}|${reply.who}|${reply.status}".hashCode(), notification)
        }
    }
}
