package studio.alans.shade

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Suggest.channel(this)
        setContent {
            val c = LocalContext.current
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(c) else dynamicLightColorScheme(c)) {
                Surface(Modifier.fillMaxSize()) { Home(install = intent?.action == Updater.ACTION_INSTALL) }
            }
        }
    }
}

@Composable
private fun Home(install: Boolean) {
    val c = LocalContext.current
    val prefs = remember { Prefs(c) }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }  // bump to re-read everything
    var note by remember { mutableStateOf(prefs.syncNote) }
    var busy by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            tick++
            note = Sync.run(c, force = false)
            Updater.check(c, force = false)
            tick++
        }
    }
    LaunchedEffect(install) { if (install && Updater.available(c) != null) { note = "Downloading the update…"; note = Updater.install(c) } }
    val update = remember(tick) { Updater.available(c) }
    val access = remember(tick) {
        NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)
    }
    val suggestions = remember(tick) { prefs.suggestions }
    val rules = remember(tick) { Rules.all(c) }
    val stats = remember(tick) { Db.get(c).stats(7) }
    val unsent = remember(tick) { Db.get(c).unsent() }

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Shade", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)

        if (!access) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Turn on notification access", fontWeight = FontWeight.SemiBold)
                    Text("Shade needs it to see notifications arrive and how they leave (tapped, swiped, cleared). " +
                        "Nothing leaves the house: it syncs only to your PC.")
                    Button(onClick = {
                        val cn = ComponentName(c, ShadeListener::class.java).flattenToString()
                        c.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn))
                    }) { Text("Open settings") }
                }
            }
        }

        update?.let { u ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Shade v${u.name} is ready", fontWeight = FontWeight.SemiBold)
                        if (u.notes.isNotBlank()) Muted(u.notes)
                    }
                    Button(onClick = { note = "Downloading the update…"; scope.launch { note = Updater.install(c) } }) { Text("Install") }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val last = prefs.lastSync.takeIf { it > 0 }?.let {
                DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            } ?: "never"
            Text("Last sync $last" + if (unsent > 0) ", $unsent to send" else "", style = MaterialTheme.typography.bodyMedium)
            if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch { note = Sync.run(c, force = true); busy = false; tick++ }
                }) { Text("Sync now") }
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch { Sync.run(c, force = true); note = Sync.review(c); busy = false; tick++ }
                }) { Text("Review now") }
            }
        }

        Section("Waiting for you")
        if (suggestions.isEmpty()) Muted("Nothing right now. Claude looks at the last two weeks every Sunday morning.")
        suggestions.forEach { sg -> SuggestionCard(sg) { approve ->
            note = if (approve && sg.optString("kind") == "channel_settings") {
                Suggest.decide(c, sg.optLong("id"), true).also {
                    OpenSettingsActivity.open(c, sg.optString("pkg"), sg.str("channel_id"))
                }
            } else Suggest.decide(c, sg.optLong("id"), approve)
            tick++
            scope.launch { Sync.run(c, force = true); tick++ }
        } }

        Section("Shade's rules")
        if (rules.isEmpty()) Muted("None yet. Approved phone rules show here.")
        rules.forEach { r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.label, fontWeight = FontWeight.Medium)
                    Muted("${appName(c, r.pkg)}: ${r.describe()}")
                }
                TextButton(onClick = { Rules.remove(c, r.id); tick++ }) { Text("Remove") }
            }
        }

        Section("Last 7 days")
        if (stats.isEmpty()) Muted(if (access) "Nothing seen yet." else "Turn on notification access to start.")
        else {
            Row(Modifier.fillMaxWidth()) {
                Text("App", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                listOf("Came", "Opened", "Swiped", "Ignored").forEach {
                    Text(it, Modifier.width(56.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
            stats.take(30).forEach { s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.app, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        s.channel?.let { Muted(it) }
                    }
                    listOf(s.count, s.opened, s.swiped, s.inShade).forEach {
                        Text("$it", Modifier.width(56.dp))
                    }
                }
            }
            Muted("Ignored = still in the shade after 6 hours. Taken back by the app counts as handled.")
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "Hide settings" else "Settings") }
        if (showSettings) {
            var url by remember { mutableStateOf(prefs.url) }
            var token by remember { mutableStateOf(prefs.token) }
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Server") }, singleLine = true)
            OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Token") }, singleLine = true)
            Button(onClick = { prefs.url = url; prefs.token = token; note = "Saved." }) { Text("Save") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Muted("Shade ${BuildConfig.VERSION_NAME}")
                TextButton(onClick = { scope.launch { note = Updater.check(c, force = true) ?: ""; tick++ } }) { Text("Check for updates") }
            }
        }
    }
}

@Composable
private fun SuggestionCard(sg: JSONObject, decide: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(sg.optString("title"), fontWeight = FontWeight.SemiBold)
            Muted(sg.optString("app").ifBlank { sg.optString("pkg") } +
                (sg.str("channel_name")?.let { ", $it" } ?: ""))
            Text(sg.optString("why"))
            Text(Suggest.describe(sg), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { decide(true) }) { Text("Approve") }
                OutlinedButton(onClick = { decide(false) }) { Text("Hold") }
            }
        }
    }
}

@Composable
private fun Section(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
    modifier = Modifier.padding(top = 6.dp))

@Composable
private fun Muted(t: String) = Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun appName(c: android.content.Context, pkg: String): String = runCatching {
    c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)
