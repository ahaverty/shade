package studio.alans.shade

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * A rule Alan approved: Shade dismisses or snoozes matching notifications as they arrive.
 * from/until = "HH:MM" window (may cross midnight); none = always. snooze needs `until`.
 */
data class Rule(val id: String, val sid: Long?, val label: String, val pkg: String, val channelId: String?,
                val titleRegex: String?, val action: String, val from: String?, val until: String?) {

    fun json(): JSONObject = JSONObject().put("id", id).put("sid", sid ?: JSONObject.NULL).put("label", label)
        .put("pkg", pkg).put("channel_id", channelId ?: JSONObject.NULL).put("title_regex", titleRegex ?: JSONObject.NULL)
        .put("action", action).put("from", from ?: JSONObject.NULL).put("until", until ?: JSONObject.NULL)

    private val regex by lazy { titleRegex?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() } }

    fun matches(pkg: String, channel: String?, title: String?, now: Calendar): Boolean {
        if (pkg != this.pkg || Rules.safety(pkg)) return false
        if (channelId != null && channel != channelId) return false
        if (titleRegex != null && (regex == null || !regex!!.containsMatchIn(title ?: ""))) return false
        return inWindow(now)
    }

    private fun mins(hhmm: String?): Int? = hhmm?.split(":")?.takeIf { it.size == 2 }
        ?.let { (h, m) -> h.toIntOrNull()?.let { hh -> m.toIntOrNull()?.let { hh * 60 + it } } }

    fun inWindow(now: Calendar): Boolean {
        val f = mins(from)
        val u = mins(until)
        if (f == null || u == null) return action != "snooze"  // a snooze needs its window
        val n = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return if (f <= u) n in f until u else n >= f || n < u
    }

    /** Milliseconds from now until `until` (next occurrence). */
    fun snoozeMs(now: Calendar): Long {
        val u = mins(until) ?: return 0
        val t = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, u / 60); set(Calendar.MINUTE, u % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        return t.timeInMillis - now.timeInMillis
    }

    fun describe(): String {
        val what = if (action == "snooze") "held back" else "dismissed"
        val window = when {
            from != null && until != null -> " from $from to $until"
            else -> ""
        }
        return "$what$window" + (titleRegex?.let { " when the title matches \"$it\"" } ?: "")
    }

    companion object {
        fun of(j: JSONObject) = Rule(j.optString("id"), if (j.isNull("sid")) null else j.optLong("sid"),
            j.optString("label"), j.getString("pkg"), j.str("channel_id"), j.str("title_regex"),
            j.optString("action", "dismiss"), j.str("from"), j.str("until"))
    }
}

fun JSONObject.str(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).ifBlank { null }

object Rules {
    /** Never touched by a rule, whatever was approved: glucose/pump alarms, calls, the alarm clock. */
    private val SAFETY = listOf("com.camdiab.", "com.dexcom.", "studio.alans.fxwatcher", "com.ypsomed.",
        "com.samsung.android.dialer", "com.google.android.dialer", "com.android.phone",
        "com.sec.android.app.clockpackage", "com.google.android.deskclock", "com.samsung.android.incallui",
        "com.android.server.telecom")

    fun safety(pkg: String?) = pkg != null && SAFETY.any { pkg.startsWith(it) }

    fun all(c: Context): List<Rule> = JSONArray(Prefs(c).p.getString("rules", "[]"))
        .let { a -> List(a.length()) { Rule.of(a.getJSONObject(it)) } }

    private fun save(c: Context, rules: List<Rule>) =
        Prefs(c).p.edit().putString("rules", JSONArray(rules.map { it.json() }).toString()).apply()

    fun json(c: Context) = JSONArray(all(c).map { it.json() })

    /** From an approved phone_rule suggestion. False if it is unusable or would touch a safety app. */
    @Synchronized
    fun addFrom(c: Context, sg: JSONObject): Boolean {
        val r = sg.optJSONObject("rule") ?: return false
        val pkg = r.str("pkg") ?: return false
        if (safety(pkg)) return false
        val action = r.optString("action").takeIf { it == "dismiss" || it == "snooze" } ?: return false
        if (action == "snooze" && (r.str("from") == null || r.str("until") == null)) return false
        val rule = Rule("s" + sg.optLong("id"), sg.optLong("id"), sg.optString("title"), pkg, r.str("channel_id"),
            r.str("title_regex"), action, r.str("from"), r.str("until"))
        save(c, all(c).filter { it.id != rule.id } + rule)
        return true
    }

    @Synchronized
    fun remove(c: Context, id: String) = save(c, all(c).filter { it.id != id })
}
