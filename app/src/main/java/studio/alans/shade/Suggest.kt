package studio.alans.shade

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Claude's suggestions as phone notifications with Approve / Hold. */
object Suggest {
    private const val CH = "suggestions"
    const val EXTRA_ID = "sid"
    const val ACTION_APPROVE = "studio.alans.shade.APPROVE"
    const val ACTION_HOLD = "studio.alans.shade.HOLD"

    fun nid(id: Long) = 1000 + (id % 100_000).toInt()

    fun channel(c: Context) = c.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CH, "Suggestions", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Claude's suggested notification changes, with Approve and Hold"
        })

    /** Each pending suggestion once; ones no longer pending are taken down. */
    fun show(c: Context, pending: List<JSONObject>) {
        channel(c)
        val nm = c.getSystemService(NotificationManager::class.java)
        val prefs = Prefs(c)
        val keep = pending.map { nid(it.optLong("id")) }.toSet()
        nm.activeNotifications.filter { it.id >= 1000 && it.id !in keep }.forEach { nm.cancel(it.id) }
        pending.filter { !prefs.notified(it.optLong("id")) }.forEach {
            post(c, it)
            prefs.markNotified(it.optLong("id"))
        }
    }

    fun describe(sg: JSONObject): String = when (sg.optString("kind")) {
        "phone_rule" -> "Approve: Shade " + (sg.optJSONObject("rule")?.let { runCatching { Rule.of(JSONObject(it.toString()).put("id", "x")).describe() }.getOrNull() }
            ?: "handles these") + "."
        "channel_settings" -> "Approve: opens ${sg.optString("app")}'s settings for this. " + sg.optString("change")
        else -> "Approve: Claude Code makes this change. " + sg.optString("change")
    }

    private fun post(c: Context, sg: JSONObject) {
        val id = sg.optLong("id")
        val approve = if (sg.optString("kind") == "channel_settings")
            PendingIntent.getActivity(c, nid(id) * 2, OpenSettingsActivity.intent(c, sg),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        else broadcast(c, ACTION_APPROVE, id, nid(id) * 2)
        val tap = PendingIntent.getActivity(c, nid(id) * 2 + 1, Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val body = sg.optString("why") + "\n" + describe(sg)
        c.getSystemService(NotificationManager::class.java).notify(nid(id), NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle(sg.optString("title"))
            .setContentText(sg.optString("why")).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap).setAutoCancel(false)
            .addAction(0, "Approve", approve)
            .addAction(0, "Hold", broadcast(c, ACTION_HOLD, id, nid(id) * 2 + 100_000))
            .build())
    }

    private fun broadcast(c: Context, action: String, id: Long, req: Int) = PendingIntent.getBroadcast(c, req,
        Intent(c, DecisionReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Approve or Hold, from the notification or the app. Returns a line for the user. */
    fun decide(c: Context, id: Long, approve: Boolean): String {
        val prefs = Prefs(c)
        val sg = prefs.suggestions.firstOrNull { it.optLong("id") == id }
        c.getSystemService(NotificationManager::class.java).cancel(nid(id))
        if (!approve) {
            prefs.queueDecision(id, "hold")
            return "Held. Claude won't suggest it again for a while."
        }
        val line = when (sg?.optString("kind")) {
            "phone_rule" -> if (Rules.addFrom(c, sg)) "Done, Shade handles these now." else {
                prefs.queueDecision(id, "hold"); return "That rule can't be used (it would touch a safety app or is incomplete), held it."
            }
            "own_system" -> "Approved. A Claude Code session starts on it once the phone reaches the server."
            else -> "Approved."
        }
        prefs.queueDecision(id, "approve")
        return line
    }
}

class DecisionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getLongExtra(Suggest.EXTRA_ID, -1)
        if (id < 0) return
        Suggest.decide(c, id, i.action == Suggest.ACTION_APPROVE)
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Sync.run(c, force = true)
            } finally {
                done.finish()
            }
        }
    }
}

/** Approve on a channel_settings suggestion: record it and open that app's notification channel page. */
class OpenSettingsActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val id = intent.getLongExtra(Suggest.EXTRA_ID, -1)
        val pkg = intent.getStringExtra("pkg") ?: ""
        val ch = intent.getStringExtra("channel")
        if (id >= 0) Suggest.decide(this, id, true)
        open(this, pkg, ch)
        CoroutineScope(Dispatchers.IO).launch { Sync.run(applicationContext, force = true) }
        finish()
    }

    companion object {
        fun intent(c: Context, sg: JSONObject) = Intent(c, OpenSettingsActivity::class.java)
            .putExtra(Suggest.EXTRA_ID, sg.optLong("id")).putExtra("pkg", sg.optString("pkg"))
            .putExtra("channel", sg.str("channel_id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** The channel's own page if Android has it, else the app's notification page, else app info. */
        fun open(c: Context, pkg: String, channel: String?) {
            val tries = listOfNotNull(
                channel?.let { Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, pkg).putExtra(Settings.EXTRA_CHANNEL_ID, it) },
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
            for (t in tries) {
                if (runCatching { c.startActivity(t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return
            }
        }
    }
}
