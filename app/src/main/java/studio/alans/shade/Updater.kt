package studio.alans.shade

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from the latest GitHub release, which the build workflow publishes (shade.apk +
 * version.json) on every v* tag. Checked at most every 6 h when the app is opened; a newer build
 * posts one quiet notification, and installing goes through Android's own installer (one
 * confirmation; the signature must match). Same design as Meal Log's Updater.
 */
object Updater {
    const val RELEASES = "https://github.com/ahaverty/shade/releases"
    private const val BASE = "$RELEASES/latest/download"
    const val ACTION_INSTALL = "studio.alans.shade.INSTALL_UPDATE"
    private const val CH = "update"
    private const val ID = 7

    data class Release(val code: Long, val name: String, val notes: String)

    fun installed(c: Context): Long =
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode }.getOrDefault(0)

    fun installedName(c: Context): String =
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?"

    /** The newer release found by the last check, or null. */
    fun available(c: Context): Release? {
        val p = Prefs(c).p
        val code = p.getLong("update_code", 0)
        return if (code > installed(c)) Release(code, p.getString("update_name", "") ?: "", p.getString("update_notes", "") ?: "") else null
    }

    /** force = the button (no 6 h wait). Returns a line for the user. */
    suspend fun check(c: Context, force: Boolean): String? = withContext(Dispatchers.IO) {
        val p = Prefs(c).p
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong("update_checked", 0) < 6 * 3_600_000L) return@withContext null
        p.edit().putLong("update_checked", now).apply()
        try {
            val j = JSONObject(String(get("$BASE/version.json")))
            val r = Release(j.getLong("versionCode"), j.optString("versionName"), j.optString("notes"))
            p.edit().putLong("update_code", r.code).putString("update_name", r.name).putString("update_notes", r.notes).apply()
            if (r.code > installed(c)) {
                if (p.getLong("update_notified", 0) != r.code) {
                    p.edit().putLong("update_notified", r.code).apply()
                    notify(c, r)
                }
                "v${r.name} is ready to install"
            } else "You have the latest version"
        } catch (e: Exception) {
            Log.w("Shade", "update check", e)
            "Couldn't check for updates: ${e.message}"
        }
    }

    private fun notify(c: Context, r: Release) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Updates", NotificationManager.IMPORTANCE_LOW))
        val tap = PendingIntent.getActivity(c, 40, Intent(c, MainActivity::class.java).setAction(ACTION_INSTALL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val body = r.notes.ifBlank { "Tap to install." }
        nm.notify(ID, NotificationCompat.Builder(c, CH).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Shade v${r.name} is ready").setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$body\nTap to install."))
            .setContentIntent(tap).setAutoCancel(true).build())
    }

    /** Downloads the APK and hands it to Android's installer. Returns a line for the user. */
    suspend fun install(c: Context): String = withContext(Dispatchers.IO) {
        try {
            val apk = get("$BASE/shade.apk")
            val installer = c.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(c.packageName)
            val id = installer.createSession(params)
            installer.openSession(id).use { s ->
                s.openWrite("shade.apk", 0, apk.size.toLong()).use { out -> out.write(apk); s.fsync(out) }
                val status = PendingIntent.getBroadcast(c, 41, Intent(c, InstallStatus::class.java),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                s.commit(status.intentSender)
            }
            c.getSystemService(NotificationManager::class.java).cancel(ID)
            "Installing… confirm on the next screen."
        } catch (e: Exception) {
            Log.w("Shade", "update install", e)
            "Update failed: ${e.message}"
        }
    }

    private fun get(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode != 200) throw Exception("GitHub said ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /** Android asks for the user's OK through this. */
    class InstallStatus : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            } else if (st != PackageInstaller.STATUS_SUCCESS) {
                Log.w("Shade", "install status $st: ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            }
        }
    }
}
