package ch.nthnbch.kalendar.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ch.nthnbch.kalendar.data.Account
import ch.nthnbch.kalendar.data.AccountStore
import ch.nthnbch.kalendar.data.Db
import ch.nthnbch.kalendar.data.StoredEvent
import ch.nthnbch.kalendar.ical.EventDraft
import ch.nthnbch.kalendar.ical.EventWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Fills the demo build with an invented account and agenda, relative to today. Never part of a real build. */
class SeedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val me = "camille@example.org"
        val db = Db(context)
        db.wipe()

        val calendars = listOf(
            Triple("/demo/perso/", "Perso", 0xFF0098FF.toInt()),
            Triple("/demo/travail/", "Travail", 0xFF9C26B0.toInt()),
            Triple("/demo/famille/", "Famille", 0xFF3EBF4D.toInt()),
        )
        calendars.forEach { (url, name, color) -> db.upsertCalendar(url, name, color, readOnly = false) }

        var n = 0
        fun add(calendar: String, title: String, day: Int, from: String?, to: String?, location: String = "", days: Int = 1,
                rrule: String? = null, guests: List<String>? = null, answers: Map<String, String> = emptyMap()) {
            val date = today.plusDays(day.toLong())
            val allDay = from == null
            val start = if (allDay) date.atStartOfDay() else LocalDateTime.of(date, LocalTime.parse(from))
            val end = if (allDay) date.plusDays(days.toLong()).atStartOfDay() else LocalDateTime.of(date, LocalTime.parse(to))
            val uid = "demo-${n++}"
            var ics = EventWriter.create(
                EventDraft(
                    title = title, location = location, allDay = allDay, start = start, end = end, zone = zone,
                    rrule = rrule, alarms = listOf(60), guests = guests, organizerEmail = me,
                ),
                uid,
            )
            for ((email, status) in answers) ics = EventWriter.respond(ics, email, status) ?: ics
            val url = "/demo/$calendar/$uid.ics"
            db.upsertEvent(StoredEvent(url, "/demo/$calendar/", "e$n", ics))
        }

        add("travail", "Point d’équipe", -14, "10:00", "10:30", "Visio", rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR")
        add("perso", "Café avec Léa", 0, "08:30", "09:00", "Café du Centre")
        add("travail", "Déjeuner client", 0, "12:30", "13:30", "Restaurant Le Lac")
        add("travail", "Présentation du projet", 0, "15:00", "16:00", "Salle B",
            guests = listOf("ana@example.org", "paul@example.org", me),
            answers = mapOf("ana@example.org" to "ACCEPTED", "paul@example.org" to "TENTATIVE"))
        add("perso", "Yoga", 0, "18:30", "19:30", "Studio Zen")
        add("famille", "Anniversaire de Marie", 1, null, null)
        add("perso", "Concert", 2, "20:00", "22:30", "Salle Métropole")
        add("perso", "Dentiste", 3, "14:00", "15:00")
        add("famille", "Week-end à la montagne", 5, null, null, days = 2)
        add("travail", "Atelier design", 6, "09:30", "12:00", "Studio")
        add("perso", "Dîner chez Paul", 8, "19:30", "22:00")
        add("famille", "Cinéma", 9, "20:15", "22:15")
        add("travail", "Revue trimestrielle", 11, "14:00", "16:00", "Salle A")
        add("perso", "Cours de piano", -3, "17:00", "18:00", rrule = "FREQ=WEEKLY")
        add("famille", "Brunch en famille", -5, "11:00", "13:00")
        add("perso", "Randonnée", 14, null, null)

        // Nothing listens on this port: the app simply stays on its stored copy.
        AccountStore(context).save(Account("http://127.0.0.1:9", "DEMO", "demo", "http://127.0.0.1:9/", "Camille Démo", me))
    }
}
