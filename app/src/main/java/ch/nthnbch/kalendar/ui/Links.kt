package ch.nthnbch.kalendar.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import ch.nthnbch.kalendar.ical.Occurrence
import java.time.LocalDate

/** Where a tap on a widget or a notification should land in the app. */
sealed interface Link {
    data class Day(val date: LocalDate) : Link
    data class Event(val href: String, val startEpochSecond: Long) : Link
    data class New(val date: LocalDate) : Link
}

/**
 * Every destination is a distinct `kalendar://` URI: PendingIntents that differ only by their extras
 * are considered identical by Android, URIs are not.
 */
object Links {

    fun day(context: Context, date: LocalDate): Intent = intent(context, "kalendar://day/${date.toEpochDay()}")

    fun event(context: Context, occurrence: Occurrence): Intent =
        event(context, occurrence.href, occurrence.startInstant.epochSecond)

    fun event(context: Context, href: String, startEpochSecond: Long): Intent = intent(
        context,
        Uri.Builder().scheme("kalendar").authority("event")
            .appendQueryParameter("href", href)
            .appendQueryParameter("start", startEpochSecond.toString())
            .build().toString(),
    )

    fun new(context: Context, date: LocalDate): Intent = intent(context, "kalendar://new/${date.toEpochDay()}")

    private fun intent(context: Context, uri: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun parse(intent: Intent?): Link? {
        val uri = intent?.data?.takeIf { it.scheme == "kalendar" } ?: return null
        return runCatching {
            when (uri.authority) {
                "day" -> Link.Day(LocalDate.ofEpochDay(uri.lastPathSegment!!.toLong()))
                "new" -> Link.New(LocalDate.ofEpochDay(uri.lastPathSegment!!.toLong()))
                "event" -> Link.Event(uri.getQueryParameter("href")!!, uri.getQueryParameter("start")!!.toLong())
                else -> null
            }
        }.getOrNull()
    }
}
