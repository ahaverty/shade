package studio.alans.shade

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Watches every notification: when it arrives, and how it leaves the shade (Android tells a listener
 * the reason: tapped, swiped, Clear all, taken back by the app...). Runs Alan's approved rules on
 * arrival. Syncs to the Shade server every 30 minutes while it can reach it.
 */
class ShadeListener : NotificationListenerService() {
    private var scope: CoroutineScope? = null
    private var loop: Job? = null
    private val labels = HashMap<String, String>()

    override fun onListenerConnected() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
        val active = runCatching { activeNotifications?.map { it.key }?.toSet() }.getOrNull() ?: emptySet()
        s.launch { Db.get(this@ShadeListener).reconcile(active, System.currentTimeMillis()) }
        loop = s.launch {
            delay(20_000)
            while (isActive) {
                Sync.run(this@ShadeListener, force = false)
                delay(30 * 60_000L)
            }
        }
    }

    override fun onListenerDisconnected() {
        scope?.cancel()
        scope = null
    }

    override fun onDestroy() {
        scope?.cancel()
        super.onDestroy()
    }

    private fun skip(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        return sbn.packageName == packageName || sbn.isOngoing ||
            n.flags and Notification.FLAG_GROUP_SUMMARY != 0 ||
            n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
    }

    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
        }.getOrDefault(pkg)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, map: RankingMap?) {
        if (skip(sbn)) return
        try {
            val r = Ranking()
            val ranked = map?.getRanking(sbn.key, r) == true
            val ch = if (ranked) r.channel else null
            val ex = sbn.notification.extras
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(120)
            // The text only for Alan's own systems (Home Assistant, his apps); for everything else the title is enough.
            val text = if (own(sbn.packageName)) (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.take(200) else null
            val seen = Seen(sbn.packageName, label(sbn.packageName), ch?.id ?: sbn.notification.channelId,
                ch?.name?.toString(), if (ranked) r.importance else -1, sbn.notification.category, title, text)
            val db = Db.get(this)
            val now = System.currentTimeMillis()
            db.posted(sbn.key, seen, sbn.postTime.takeIf { it > 0 } ?: now)

            val cal = Calendar.getInstance()
            val rule = Rules.all(this).firstOrNull { it.matches(sbn.packageName, seen.channelId, title, cal) } ?: return
            db.removed(sbn.key, "ruled", now)  // before Android's own removal callback, which is then ignored
            if (rule.action == "snooze") snoozeNotification(sbn.key, rule.snoozeMs(cal)) else cancelNotification(sbn.key)
        } catch (e: Exception) {
            Log.w("Shade", "posted", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, map: RankingMap?, reason: Int) {
        if (skip(sbn)) return
        try {
            Db.get(this).removed(sbn.key, outcome(reason), System.currentTimeMillis())
        } catch (e: Exception) {
            Log.w("Shade", "removed", e)
        }
    }

    companion object {
        fun own(pkg: String) = pkg.startsWith("io.homeassistant.companion.android") ||
            pkg.startsWith("studio.alans.") || pkg.startsWith("com.ahaverty.")

        fun outcome(reason: Int): String = when (reason) {
            REASON_CLICK -> "opened"
            REASON_CANCEL, REASON_GROUP_SUMMARY_CANCELED -> "swiped"
            REASON_CANCEL_ALL -> "cleared"
            REASON_APP_CANCEL, REASON_APP_CANCEL_ALL -> "app_removed"
            REASON_TIMEOUT -> "timeout"
            REASON_SNOOZED -> "snoozed"
            REASON_LISTENER_CANCEL, REASON_LISTENER_CANCEL_ALL -> "ruled"
            REASON_CHANNEL_BANNED, REASON_PACKAGE_BANNED -> "blocked"
            else -> "other"
        }
    }
}
