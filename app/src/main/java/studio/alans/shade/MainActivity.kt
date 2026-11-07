package studio.alans.shade

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Calendar

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        enableEdgeToEdge()
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

/** How a notification left, in display order, with its colour. */
private enum class Out(val label: String, val color: Color) {
    Opened("Opened", Color(0xFF2E9D5B)),
    Handled("Handled", Color(0xFF3B7BD9)),
    Swiped("Swiped", Color(0xFFE08A2E)),
    Ignored("Ignored", Color(0xFFD0453A)),
    Ruled("Shade rule", Color(0xFF8A5CD0)),
    Other("Other", Color(0xFF9AA0A6)),
}

private fun AppStat.parts(): Map<Out, Int> {
    val known = opened + handled + swiped + inShade + ruled
    return mapOf(Out.Opened to opened, Out.Handled to handled, Out.Swiped to swiped, Out.Ignored to inShade,
        Out.Ruled to ruled, Out.Other to (count - known).coerceAtLeast(0))
}

private fun List<AppStat>.total(app: String) = AppStat(first().pkg, app, null, sumOf { it.count }, sumOf { it.opened },
    sumOf { it.swiped }, sumOf { it.handled }, sumOf { it.inShade }, sumOf { it.ruled })

private enum class Period(val label: String) { Today("Today"), Week("7 days"), Month("30 days") }

private fun Period.since(): Long = when (this) {
    Period.Today -> Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    Period.Week -> System.currentTimeMillis() - 7 * 86_400_000L
    Period.Month -> System.currentTimeMillis() - 30 * 86_400_000L
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Home(install: Boolean) {
    val c = LocalContext.current
    val prefs = remember { Prefs(c) }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }  // bump to re-read everything
    var note by remember { mutableStateOf(prefs.syncNote) }
    var busy by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var period by remember { mutableStateOf(Period.Week) }
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
    val access = remember(tick) { NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName) }
    val suggestions = remember(tick) { prefs.suggestions }
    val rules = remember(tick) { Rules.all(c) }
    val stats = remember(tick, period) { Db.get(c).statsSince(period.since()) }
    val apps = remember(stats) {
        stats.groupBy { it.pkg }.map { (_, ch) -> ch.total(ch.first().app) to ch.sortedByDescending { it.count } }
            .sortedByDescending { it.first.count }
    }
    val unsent = remember(tick) { Db.get(c).unsent() }
    val last = prefs.lastSync.takeIf { it > 0 }?.let {
        DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }

    fun sync() { busy = true; scope.launch { note = Sync.run(c, force = true); busy = false; tick++ } }

    val bar = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(bar.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Shade", fontWeight = FontWeight.SemiBold)
                        Text((last?.let { "Synced $it" } ?: "Not synced yet") + if (unsent > 0) " · $unsent waiting" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = ::sync) { Icon(Icons.Filled.Refresh, "Sync now") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, "Settings") }
                },
                scrollBehavior = bar,
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp,
                bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (note.isNotBlank() && !note.startsWith("Synced")) item {
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (!access) item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Turn on notification access", style = MaterialTheme.typography.titleMedium)
                        Text("Shade needs it to see notifications arrive and how they leave. " +
                            "Nothing leaves the house: it only syncs to your PC.", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = {
                            val cn = ComponentName(c, ShadeListener::class.java).flattenToString()
                            c.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn))
                        }) { Text("Open settings") }
                    }
                }
            }

            update?.let { u -> item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Shade ${u.name} is ready", fontWeight = FontWeight.SemiBold)
                            if (u.notes.isNotBlank()) Text(u.notes, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.width(12.dp))
                        Button(onClick = { note = "Downloading the update…"; scope.launch { note = Updater.install(c) } }) { Text("Install") }
                    }
                }
            } }

            item {
                Header("Waiting for you", action = "Ask Claude now", busy = busy) {
                    busy = true
                    scope.launch { Sync.run(c, force = true); note = Sync.review(c); busy = false; tick++ }
                }
            }
            if (suggestions.isEmpty()) item {
                Empty("Nothing to decide. Claude looks at the last two weeks every Sunday at 10 and sends anything worth changing here.")
            }
            items(suggestions, key = { "s" + it.optLong("id") }) { sg ->
                SuggestionCard(sg) { approve ->
                    note = if (approve && sg.optString("kind") == "channel_settings") {
                        Suggest.decide(c, sg.optLong("id"), true).also {
                            OpenSettingsActivity.open(c, sg.optString("pkg"), sg.str("channel_id"))
                        }
                    } else Suggest.decide(c, sg.optLong("id"), approve)
                    tick++
                    scope.launch { Sync.run(c, force = true); tick++ }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Header("Your notifications")
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Period.entries.forEachIndexed { i, p ->
                        SegmentedButton(selected = period == p, onClick = { period = p },
                            shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size)) { Text(p.label) }
                    }
                }
            }
            if (apps.isEmpty()) item {
                Empty(if (access) "Nothing seen in this period yet." else "Turn on notification access to start.")
            } else {
                item { Overview(apps.map { it.first }) }
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            apps.take(40).forEachIndexed { i, (total, channels) ->
                                if (i > 0) HorizontalDivider(Modifier.padding(start = 64.dp))
                                AppRow(total, channels)
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Header("Shade's rules")
            }
            if (rules.isEmpty()) item { Empty("None yet. Phone rules you approve show here, and you can remove them any time.") }
            else item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        rules.forEachIndexed { i, r ->
                            if (i > 0) HorizontalDivider(Modifier.padding(start = 64.dp))
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                AppIcon(r.pkg)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(r.label, fontWeight = FontWeight.Medium)
                                    Small("${appName(c, r.pkg)}: ${r.describe()}")
                                }
                                TextButton(onClick = { Rules.remove(c, r.id); tick++ }) { Text("Remove") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettings) SettingsDialog(prefs, onDismiss = { showSettings = false }, onSaved = { note = "Saved." },
        onCheck = { scope.launch { note = Updater.check(c, force = true) ?: "Shade is up to date."; tick++ } })
}

/** Totals across all apps: a headline and one stacked bar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Overview(apps: List<AppStat>) {
    val all = AppStat("", "", null, apps.sumOf { it.count }, apps.sumOf { it.opened }, apps.sumOf { it.swiped },
        apps.sumOf { it.handled }, apps.sumOf { it.inShade }, apps.sumOf { it.ruled })
    val parts = all.parts()
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${all.count}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text("notifications from ${apps.size} app" + if (apps.size == 1) "" else "s",
                    Modifier.padding(bottom = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StackBar(parts, all.count, 12.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                parts.filter { it.value > 0 }.forEach { (o, n) -> Legend(o, "${o.label} ${pct(n, all.count)}") }
            }
            Small("Handled = the app took it back (you dealt with it elsewhere). Ignored = still in the shade after 6 hours.")
        }
    }
}

@Composable
private fun AppRow(total: AppStat, channels: List<AppStat>) {
    var open by remember { mutableStateOf(false) }
    val many = channels.size > 1 || channels.first().channel != null
    Column(Modifier.fillMaxWidth().clickable(enabled = many) { open = !open }
        .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(total.pkg)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(total.app, Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${total.count}", style = MaterialTheme.typography.titleSmall)
                }
                StackBar(total.parts(), total.count, 6.dp)
                Small(summary(total))
            }
            if (many) Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null,
                Modifier.padding(start = 4.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 52.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                channels.forEach { ch ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row {
                            Text(ch.channel ?: "No channel", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${ch.count}", style = MaterialTheme.typography.bodyMedium)
                        }
                        StackBar(ch.parts(), ch.count, 4.dp)
                        Small(summary(ch))
                    }
                }
            }
        }
    }
}

/** "Opened 3 · Swiped 8": the non-zero outcomes, biggest first. */
private fun summary(s: AppStat): String = s.parts().filter { it.value > 0 && it.key != Out.Other }
    .entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key.label} ${it.value}" }
    .ifBlank { "Still showing" }

private fun pct(n: Int, of: Int) = if (of == 0) "0%" else "${(n * 100f / of).let { if (it in 0.1f..0.99f) 1 else it.toInt() }}%"

@Composable
private fun StackBar(parts: Map<Out, Int>, total: Int, h: androidx.compose.ui.unit.Dp) {
    Row(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(h / 2))
        .background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (total > 0) parts.forEach { (o, n) ->
            if (n > 0) Box(Modifier.weight(n.toFloat()).fillMaxSize().background(o.color))
        }
    }
}

@Composable
private fun Legend(o: Out, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(o.color))
    Spacer(Modifier.width(6.dp))
    Text(text, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SuggestionCard(sg: JSONObject, decide: (Boolean) -> Unit) {
    val pkg = sg.optString("pkg")
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pkg.isNotBlank()) { AppIcon(pkg, 32.dp); Spacer(Modifier.width(10.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(sg.optString("title"), style = MaterialTheme.typography.titleMedium)
                    Small(sg.optString("app").ifBlank { pkg } + (sg.str("channel_name")?.let { " · $it" } ?: ""))
                }
            }
            Text(sg.optString("why"), style = MaterialTheme.typography.bodyMedium)
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
                Text(Suggest.describe(sg), Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { decide(false) }) { Text("Hold") }
                FilledTonalButton(onClick = { decide(true) }) { Text("Approve") }
            }
        }
    }
}

@Composable
private fun SettingsDialog(prefs: Prefs, onDismiss: () -> Unit, onSaved: () -> Unit, onCheck: () -> Unit) {
    var url by remember { mutableStateOf(prefs.url) }
    var token by remember { mutableStateOf(prefs.token) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Server") }, singleLine = true)
                OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Token") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Small("Shade ${BuildConfig.VERSION_NAME}", Modifier.weight(1f))
                    TextButton(onClick = onCheck) { Text("Check for updates") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { prefs.url = url; prefs.token = token; onSaved(); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun Header(t: String, action: String? = null, busy: Boolean = false, onAction: () -> Unit = {}) =
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (action != null) TextButton(enabled = !busy, onClick = onAction) { Text(action) }
    }

@Composable
private fun Empty(t: String) = Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape = RoundedCornerShape(12.dp)) {
    Text(t, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Small(t: String, modifier: Modifier = Modifier) = Text(t, modifier, style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)

private val icons = HashMap<String, ImageBitmap?>()

@Composable
private fun AppIcon(pkg: String, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val c = LocalContext.current
    val bmp = remember(pkg) {
        icons.getOrPut(pkg) {
            runCatching { c.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    if (bmp != null) Image(bmp, null, Modifier.size(size).clip(CircleShape))
    else Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center) {
        Text(pkg.substringAfterLast('.').take(1).uppercase(), color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

private fun appName(c: Context, pkg: String): String = runCatching {
    c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)
