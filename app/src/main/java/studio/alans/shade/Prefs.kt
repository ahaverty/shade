package studio.alans.shade

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Prefs(c: Context) {
    val p = c.getSharedPreferences("shade", Context.MODE_PRIVATE)

    init {
        // Private builds carry the server settings (secrets.properties); copy them in once.
        if (!p.getBoolean("baked", false)) {
            p.edit().putBoolean("baked", true)
                .putString("url", BuildConfig.SERVER_URL.ifBlank { "http://192.168.0.100:8795" })
                .putString("token", BuildConfig.SERVER_TOKEN).apply()
        }
    }

    var url: String
        get() = (p.getString("url", "") ?: "").trim().trimEnd('/')
        set(v) = p.edit().putString("url", v.trim()).apply()
    var token: String
        get() = (p.getString("token", "") ?: "").trim()
        set(v) = p.edit().putString("token", v.trim()).apply()

    var lastSync: Long
        get() = p.getLong("last_sync", 0)
        set(v) = p.edit().putLong("last_sync", v).apply()
    var lastTry: Long
        get() = p.getLong("last_try", 0)
        set(v) = p.edit().putLong("last_try", v).apply()
    var syncNote: String
        get() = p.getString("sync_note", "") ?: ""
        set(v) = p.edit().putString("sync_note", v).apply()

    /** Pending suggestions from the server's last answer. */
    var suggestions: List<JSONObject>
        get() = JSONArray(p.getString("suggestions", "[]")).let { a -> List(a.length()) { a.getJSONObject(it) } }
        set(v) = p.edit().putString("suggestions", JSONArray(v).toString()).apply()

    /** Decisions not yet sent: [{id, decision}]. */
    @Synchronized
    fun queueDecision(id: Long, decision: String) {
        val a = JSONArray(p.getString("decisions", "[]"))
        a.put(JSONObject().put("id", id).put("decision", decision))
        p.edit().putString("decisions", a.toString()).apply()
        // Hide it from the list straight away.
        suggestions = suggestions.filter { it.optLong("id") != id }
    }

    fun decisions(): JSONArray = JSONArray(p.getString("decisions", "[]"))

    @Synchronized
    fun decisionsSent(ids: Set<Long>) {
        val a = decisions()
        val keep = JSONArray()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optLong("id") !in ids) keep.put(a.getJSONObject(i))
        p.edit().putString("decisions", keep.toString()).apply()
    }

    fun notified(id: Long): Boolean = p.getStringSet("notified", emptySet())!!.contains(id.toString())
    fun markNotified(id: Long) {
        p.edit().putStringSet("notified", p.getStringSet("notified", emptySet())!! + id.toString()).apply()
    }
}
