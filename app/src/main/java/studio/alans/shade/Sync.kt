package studio.alans.shade

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Upload new and changed events plus Alan's decisions, get the pending suggestions back.
 * The server is on the home LAN: away from home (no WireGuard) this just fails and tries again later.
 */
object Sync {
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val one = Mutex()

    /** Returns a line for the user. */
    suspend fun run(c: Context, force: Boolean): String = one.withLock {
        withContext(Dispatchers.IO) {
            val prefs = Prefs(c)
            if (prefs.url.isBlank() || prefs.token.isBlank()) return@withContext "Set the server in Settings first.".also { prefs.syncNote = it }
            val now = System.currentTimeMillis()
            if (!force && now - prefs.lastTry < 10 * 60_000L) return@withContext prefs.syncNote
            prefs.lastTry = now
            try {
                var saved = 0
                var decided = setOf<Long>()
                var answer: JSONObject
                val db = Db.get(c)
                do {
                    val rows = db.dirty()
                    val decisions = prefs.decisions()
                    val body = JSONObject().put("device", Build.MODEL).put("version", BuildConfig.VERSION_NAME)
                        .put("rules", Rules.json(c)).put("events", JSONArray(rows.map { it.second }))
                        .put("decisions", decisions)
                    answer = post(prefs, "/api/sync", body)
                    db.sent(rows.map { it.first })
                    val ids = answer.optJSONArray("decided")?.let { a -> List(a.length()) { a.getLong(it) }.toSet() } ?: emptySet()
                    prefs.decisionsSent(ids)
                    decided = decided + ids
                    saved += rows.size
                } while (rows.size >= 800)

                val pending = answer.optJSONArray("suggestions")?.let { a -> List(a.length()) { a.getJSONObject(it) } } ?: emptyList()
                val waiting = prefs.decisions().let { a -> List(a.length()) { a.getJSONObject(it).optLong("id") }.toSet() }
                prefs.suggestions = pending.filter { it.optLong("id") !in waiting }
                Suggest.show(c, prefs.suggestions)
                prefs.lastSync = System.currentTimeMillis()
                val n = prefs.suggestions.size
                ("Synced $saved" + (if (n > 0) ", $n suggestion${if (n == 1) "" else "s"} waiting" else "")).also { prefs.syncNote = it }
            } catch (e: Exception) {
                Log.w("Shade", "sync", e)
                val why = when (e) {
                    is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.NoRouteToHostException ->
                        "Can't reach the server (away from home?). Will try again."
                    else -> "Sync failed: ${e.message}"
                }
                why.also { prefs.syncNote = it }
            }
        }
    }

    fun post(prefs: Prefs, path: String, body: JSONObject): JSONObject {
        val req = Request.Builder().url(prefs.url + path).header("Authorization", "Bearer ${prefs.token}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) throw Exception("server said ${r.code} ${text.take(120)}")
            return JSONObject(text.ifBlank { "{}" })
        }
    }

    suspend fun review(c: Context): String = withContext(Dispatchers.IO) {
        try {
            post(Prefs(c), "/api/review", JSONObject())
            "Review started. Suggestions show up here and as notifications in a few minutes."
        } catch (e: Exception) {
            "Couldn't start a review: ${e.message}"
        }
    }
}
