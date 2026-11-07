package studio.alans.shade

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** One notification while it was in the shade, and how it left (outcome; null = still there). */
data class Seen(val pkg: String, val app: String, val channelId: String?, val channelName: String?, val importance: Int,
                val category: String?, val title: String?, val text: String?)

data class AppStat(val pkg: String, val app: String, val channel: String?, val count: Int, val opened: Int,
                   val swiped: Int, val handled: Int, val inShade: Int, val ruled: Int)

/**
 * Local log of every notification. `dirty` counts changes since the last upload, so a change that
 * lands while a sync is in flight is not marked as sent.
 */
class Db private constructor(c: Context) : SQLiteOpenHelper(c, "shade.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE events (eid INTEGER PRIMARY KEY AUTOINCREMENT, key TEXT NOT NULL, pkg TEXT NOT NULL,
            app TEXT, channel_id TEXT, channel_name TEXT, importance INTEGER, category TEXT, title TEXT, text TEXT,
            posted_at INTEGER NOT NULL, updates INTEGER NOT NULL DEFAULT 0, removed_at INTEGER, outcome TEXT,
            dirty INTEGER NOT NULL DEFAULT 1)""")
        db.execSQL("CREATE INDEX events_open ON events(key) WHERE removed_at IS NULL")
        db.execSQL("CREATE INDEX events_posted ON events(posted_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun openEid(key: String): Long? = readableDatabase.rawQuery(
        "SELECT eid FROM events WHERE key = ? AND removed_at IS NULL ORDER BY eid DESC LIMIT 1", arrayOf(key)
    ).use { if (it.moveToFirst()) it.getLong(0) else null }

    /** A notification was posted. A repost of one still showing counts as an update, not a new one. */
    @Synchronized
    fun posted(key: String, s: Seen, at: Long): Long {
        val open = openEid(key)
        if (open != null) {
            writableDatabase.execSQL("UPDATE events SET updates = updates + 1, title = ?, text = ?, dirty = dirty + 1 WHERE eid = ?",
                arrayOf(s.title, s.text, open))
            return open
        }
        return writableDatabase.insert("events", null, ContentValues().apply {
            put("key", key); put("pkg", s.pkg); put("app", s.app); put("channel_id", s.channelId)
            put("channel_name", s.channelName); put("importance", s.importance); put("category", s.category)
            put("title", s.title); put("text", s.text); put("posted_at", at)
        })
    }

    /** It left the shade. Only the open one with that key is closed. */
    @Synchronized
    fun removed(key: String, outcome: String, at: Long) {
        val open = openEid(key) ?: return
        writableDatabase.execSQL("UPDATE events SET removed_at = ?, outcome = ?, dirty = dirty + 1 WHERE eid = ?",
            arrayOf(at, outcome, open))
    }

    /** After the listener reconnects: anything we think is showing but isn't went while we weren't listening. */
    @Synchronized
    fun reconcile(active: Set<String>, at: Long) {
        val gone = readableDatabase.rawQuery("SELECT eid, key FROM events WHERE removed_at IS NULL", null).use { c ->
            buildList { while (c.moveToNext()) if (c.getString(1) !in active) add(c.getLong(0)) }
        }
        gone.forEach {
            writableDatabase.execSQL("UPDATE events SET removed_at = ?, outcome = 'other', dirty = dirty + 1 WHERE eid = ?",
                arrayOf(at, it))
        }
    }

    /** Changed rows to upload: (eid, dirty) pairs and their JSON. */
    fun dirty(limit: Int = 800): List<Pair<Pair<Long, Int>, JSONObject>> = readableDatabase.rawQuery(
        "SELECT * FROM events WHERE dirty > 0 ORDER BY eid LIMIT $limit", null
    ).use { c ->
        fun str(n: String) = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getString(it) }
        fun lng(n: String) = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getLong(it) }
        buildList {
            while (c.moveToNext()) {
                val j = JSONObject().put("eid", lng("eid")).put("pkg", str("pkg")).put("app", str("app"))
                    .put("channel_id", str("channel_id")).put("channel_name", str("channel_name"))
                    .put("importance", lng("importance")).put("category", str("category")).put("title", str("title"))
                    .put("text", str("text")).put("posted_at", lng("posted_at")).put("updates", lng("updates"))
                    .put("removed_at", lng("removed_at") ?: JSONObject.NULL).put("outcome", str("outcome") ?: JSONObject.NULL)
                add((lng("eid")!! to lng("dirty")!!.toInt()) to j)
            }
        }
    }

    @Synchronized
    fun sent(rows: List<Pair<Long, Int>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { (eid, d) -> db.execSQL("UPDATE events SET dirty = 0 WHERE eid = ? AND dirty = ?", arrayOf(eid, d)) }
            // Keep 60 days on the phone; the server keeps the history.
            db.execSQL("DELETE FROM events WHERE dirty = 0 AND posted_at < ?",
                arrayOf(System.currentTimeMillis() - 60L * 86_400_000))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun unsent(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM events WHERE dirty > 0", null)
        .use { it.moveToFirst(); it.getInt(0) }

    fun stats(days: Int): List<AppStat> = statsSince(System.currentTimeMillis() - days * 86_400_000L)

    fun statsSince(since: Long): List<AppStat> {
        val sixH = System.currentTimeMillis() - 6 * 3_600_000L
        return readableDatabase.rawQuery("""SELECT pkg, MAX(app), MAX(channel_name), COUNT(*),
              SUM(outcome = 'opened'), SUM(outcome IN ('swiped', 'cleared')), SUM(outcome = 'app_removed'),
              SUM(outcome IS NULL AND posted_at < ?), SUM(outcome = 'ruled')
            FROM events WHERE posted_at >= ? GROUP BY pkg, channel_id ORDER BY COUNT(*) DESC""",
            arrayOf(sixH.toString(), since.toString())).use { c ->
            buildList {
                while (c.moveToNext()) add(AppStat(c.getString(0), c.getString(1) ?: c.getString(0), c.getString(2),
                    c.getInt(3), c.getInt(4), c.getInt(5), c.getInt(6), c.getInt(7), c.getInt(8)))
            }
        }
    }

    companion object {
        @Volatile private var one: Db? = null
        fun get(c: Context): Db = one ?: synchronized(this) { one ?: Db(c.applicationContext).also { one = it } }
    }
}
