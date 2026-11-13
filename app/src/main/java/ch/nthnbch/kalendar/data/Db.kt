package ch.nthnbch.kalendar.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class CalendarInfo(
    val url: String,
    val name: String,
    val color: Int,
    val readOnly: Boolean,
    val visible: Boolean,
    val ctag: String?,
)

data class StoredEvent(val href: String, val calendarUrl: String, val etag: String?, val ics: String)

/** Local mirror of the server: the calendars and the raw .ics of every event. */
class Db(context: Context) : SQLiteOpenHelper(context, "kalendar.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE calendars (url TEXT PRIMARY KEY, name TEXT NOT NULL, color INTEGER NOT NULL, " +
                "read_only INTEGER NOT NULL, visible INTEGER NOT NULL DEFAULT 1, ctag TEXT)",
        )
        db.execSQL("CREATE TABLE events (href TEXT PRIMARY KEY, calendar_url TEXT NOT NULL, etag TEXT, ics TEXT NOT NULL)")
        db.execSQL("CREATE INDEX events_calendar ON events (calendar_url)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun calendars(): List<CalendarInfo> = readableDatabase
        .rawQuery("SELECT url, name, color, read_only, visible, ctag FROM calendars ORDER BY name COLLATE NOCASE", null)
        .use { c ->
            val out = ArrayList<CalendarInfo>()
            while (c.moveToNext()) {
                out += CalendarInfo(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3) != 0, c.getInt(4) != 0, c.getString(5))
            }
            out
        }

    fun events(): List<StoredEvent> = readableDatabase
        .rawQuery("SELECT href, calendar_url, etag, ics FROM events", null)
        .use { c ->
            val out = ArrayList<StoredEvent>()
            while (c.moveToNext()) out += StoredEvent(c.getString(0), c.getString(1), c.getString(2), c.getString(3))
            out
        }

    fun etags(calendarUrl: String): Map<String, String?> = readableDatabase
        .rawQuery("SELECT href, etag FROM events WHERE calendar_url = ?", arrayOf(calendarUrl))
        .use { c ->
            val out = HashMap<String, String?>()
            while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
            out
        }

    /** Inserts or refreshes a calendar while keeping the user's own "visible" choice. */
    fun upsertCalendar(url: String, name: String, color: Int, readOnly: Boolean) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("name", name)
            put("color", color)
            put("read_only", if (readOnly) 1 else 0)
        }
        if (db.update("calendars", values, "url = ?", arrayOf(url)) == 0) {
            values.put("url", url)
            db.insert("calendars", null, values)
        }
    }

    fun setCtag(url: String, ctag: String?) {
        writableDatabase.update("calendars", ContentValues().apply { put("ctag", ctag) }, "url = ?", arrayOf(url))
    }

    fun setVisible(url: String, visible: Boolean) {
        writableDatabase.update("calendars", ContentValues().apply { put("visible", if (visible) 1 else 0) }, "url = ?", arrayOf(url))
    }

    fun deleteCalendar(url: String) {
        writableDatabase.delete("events", "calendar_url = ?", arrayOf(url))
        writableDatabase.delete("calendars", "url = ?", arrayOf(url))
    }

    fun upsertEvent(event: StoredEvent) {
        val values = ContentValues().apply {
            put("href", event.href)
            put("calendar_url", event.calendarUrl)
            put("etag", event.etag)
            put("ics", event.ics)
        }
        writableDatabase.insertWithOnConflict("events", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteEvent(href: String) {
        writableDatabase.delete("events", "href = ?", arrayOf(href))
    }

    fun <T> transaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    fun wipe() {
        writableDatabase.delete("events", null, null)
        writableDatabase.delete("calendars", null, null)
    }
}
