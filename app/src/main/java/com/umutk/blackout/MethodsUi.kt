package com.umutk.blackout

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val PRIVACY = "No data is shared. BlackOut has no internet permission: nothing you do here ever leaves your phone."

/** The privacy promise, shown on the home screen, in About and on the method pages. */
@Composable
fun PrivacyNote(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0F1A12)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Lock, null, tint = Color(0xFF66BB6A), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(PRIVACY, fontSize = 13.sp, color = Color(0xFFB9E4BE))
    }
}

/** Is the accessibility service on? With buttons to switch it on (through root, or by hand in the system settings). */
@Composable
fun AccessRow(mode: Privilege.Mode) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(AppWatch.enabled(ctx)) }
    var msg by remember { mutableStateOf<String?>(null) }
    Column {
        Text(if (on) "Accessibility service is on" else "Accessibility service is off", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (on) Color(0xFF4CAF50) else Color(0xFFFFB74D))
        Text("It sees where text and images are, never what they say. Nothing is stored or sent.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!on) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                scope.launch {
                    val o = withContext(Dispatchers.IO) { AppWatch.enableWithPower() }
                    on = AppWatch.enabled(ctx); msg = if (on) null else "Root could not switch it on: ${o.text.take(100)}"
                }
            }, enabled = mode == Privilege.Mode.Root || mode == Privilege.Mode.Shizuku) { Text("Turn on (root)") }
            if (AppWatch.canSelfEnable(ctx)) Button({ AppWatch.enableSelf(ctx); on = AppWatch.enabled(ctx); if (!on) msg = "Android did not switch it on. Open settings instead." }) { Text("Turn on (one tap)") }
            OutlinedButton({ ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open settings") }
            TextButton({ on = AppWatch.enabled(ctx) }) { Text("Refresh") }
        }
        msg?.let { Text(it, fontSize = 13.sp, color = Color(0xFFF44336)) }
        if (!on && !AppWatch.canSelfEnable(ctx) && mode == Privilege.Mode.None) {
            Text("No root? Run this once from a computer (or Shizuku/wireless adb), then the button above works by itself:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Text(AppWatch.ADB_GRANT, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            TextButton({ (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("adb", AppWatch.ADB_GRANT)) }) { Text("Copy command") }
        }
    }
}

private val LayerColors = listOf("Black" to 0x000000, "Navy" to 0x050A18, "Warm" to 0x140E08, "Forest" to 0x07120A, "Plum" to 0x120818)

/** Top layer through accessibility: works without root. */
@Composable
fun LayerPage(mode: Privilege.Mode, sp: SharedPreferences) {
    var on by remember { mutableStateOf(sp.getBoolean("ly_on", false)) }
    var alpha by remember { mutableFloatStateOf(sp.getInt("ly_alpha", 45).toFloat()) }
    var color by remember { mutableIntStateOf(sp.getInt("ly_color", 0x000000) and 0xFFFFFF) }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(16.dp)) {
                Text("Preview", fontWeight = FontWeight.Bold)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { MockPage(null, Modifier.fillMaxWidth()) }
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))) {
                        MockPage(null, Modifier.fillMaxWidth())
                        Box(Modifier.matchParentSize().background(Color(0xFF000000.toInt() or color).copy(alpha = alpha / 100f)))
                    }
                }
            }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (on) "Top layer is ON (all apps)" else "Top layer is off", fontWeight = FontWeight.Bold)
                        Text("Dims everything. You can also set it per app.", fontSize = 13.sp, color = dim)
                    }
                    Switch(on, { on = it; sp.edit().putBoolean("ly_on", it).apply() })
                }
                var protect by remember { mutableStateOf(sp.getBoolean("ly_protect", true)) }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep text and images bright", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("Cuts holes in the layer over text and images. Best on dark apps; on white pages the patches around the text stay light.", fontSize = 13.sp, color = dim)
                    }
                    Switch(protect, { protect = it; sp.edit().putBoolean("ly_protect", it).apply() })
                }
                Text("Darkness ${alpha.toInt()}%", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                Slider(alpha, { alpha = it }, valueRange = 0f..92f, onValueChangeFinished = { sp.edit().putInt("ly_alpha", alpha.toInt()).apply() })
                LayerPreview(alpha, color)
                Text("Colour", fontSize = 14.sp)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LayerColors.forEach { (_, c) ->
                        Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or c)).border(if (color == c) 3.dp else 1.dp, if (color == c) Color(0xFF8C9EFF) else Color(0x66FFFFFF), CircleShape)
                            .clickable { color = c; sp.edit().putInt("ly_color", c).apply() })
                    }
                }
            }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(16.dp)) {
                AccessRow(mode)
                Text("It dims evenly: greys get darker and white turns grey. For pure black use Dark pages or the module.", fontSize = 13.sp, color = dim, modifier = Modifier.padding(top = 8.dp))
            }
        }
        PrivacyNote()
    }
}

/** In an app's page: show the top layer only while this app is open. */
@Composable
fun LayerAppCard(pkg: String) {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("blackout", Context.MODE_PRIVATE) }
    var apps by remember { mutableStateOf(sp.getStringSet("ly_apps", emptySet()) ?: emptySet()) }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(vertical = 6.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Top layer while this app is open", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text("No root needed. Set darkness and colour in Top layer.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(pkg in apps, { v -> apps = if (v) apps + pkg else apps - pkg; sp.edit().putStringSet("ly_apps", apps).apply() })
        }
    }
}

/** LSPosed module: how to switch it on, its settings and whether it is active. */
@Composable
fun ModulePage(mode: Privilege.Mode, sp: SharedPreferences) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(sp.getBoolean("lsp_on", true)) }
    var limit by remember { mutableFloatStateOf(sp.getFloat("limit", 56f)) }
    var keep by remember { mutableFloatStateOf(sp.getFloat("keep", 0f)) }
    var tint by remember { mutableStateOf(sp.getInt("tint", 0)) }
    var active by remember { mutableStateOf(Status.isActive()) }
    var msg by remember { mutableStateOf<String?>(null) }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (active) Color(0xFF4CAF50) else Color(0xFFFFB74D)))
                    Spacer(Modifier.width(10.dp))
                    Text(if (active) "Module is active in LSPosed" else "Module not detected yet", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton({ active = Status.isActive() }) { Text("Refresh") }
                }
                Text("Turns the dark greys of the apps you tick into pure black. Works even when an app hides its colour names.", fontSize = 13.sp, color = dim, modifier = Modifier.padding(top = 4.dp))
                Text("1. In LSPosed, open Modules → BlackOut and switch it on.\n2. Tick the apps to black out, and tick BlackOut too.\n3. Close those apps and open them again.", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({
                        val i = ctx.packageManager.getLaunchIntentForPackage("org.lsposed.manager")
                        if (i != null) ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        else msg = "LSPosed is not installed, or it has no launcher icon: open it from its notification, or dial *#*#5776733#*#*."
                    }) { Text("Open LSPosed") }
                }
                msg?.let { Text(it, fontSize = 13.sp, color = dim, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Apply in the ticked apps", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Switch(on, { on = it; sp.edit().putBoolean("lsp_on", it).apply() })
                }
                Text("Which greys count as surfaces", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                GreySlider(limit, { limit = it }) { sp.edit().putFloat("limit", limit).apply() }
                Text(if (keep < 1f) "Result: pure black" else "Result: ${keep.toInt()}% of the brightness stays", fontSize = 13.sp)
                Slider(keep, { keep = it }, valueRange = 0f..40f, onValueChangeFinished = { sp.edit().putFloat("keep", keep).apply() })
                TintPicker(tint) { tint = it; sp.edit().putInt("tint", it).apply() }
                GreyPreview(limit, keep, tint)
                var web by remember { mutableStateOf(sp.getBoolean("web_black", true)) }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Web pages inside apps too", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text("The Google app's results, mail bodies and in-app browsers are web pages: Android's colour calls never reach them, so a small script blackens their dark grey backgrounds (same limit).", fontSize = 13.sp, color = dim)
                    }
                    Switch(web, { web = it; sp.edit().putBoolean("web_black", it).apply() })
                }
                Text("Changes apply when an app starts. If an app ignores them:", fontSize = 13.sp, color = dim)
                OutlinedButton({
                    scope.launch {
                        val o = withContext(Dispatchers.IO) { Privilege.run("chmod 755 /data/data/${ctx.packageName}; chmod 755 /data/data/${ctx.packageName}/shared_prefs; chmod 644 /data/data/${ctx.packageName}/shared_prefs/blackout.xml; echo ok") }
                        msg = if (o.text.contains("ok")) "Settings are readable by the module now." else "Failed: ${o.text.take(100)}"
                    }
                }, enabled = mode != Privilege.Mode.None) { Text("Fix settings access (root)") }
            }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(16.dp)) {
                val fd = sp.getStringSet("fd_apps", emptySet())?.size ?: 0
                val pg = sp.getStringSet("pgmod_apps", emptySet())?.size ?: 0
                Text("Apps without a dark theme", fontWeight = FontWeight.Bold)
                Text("The greys above only exist in apps that have a dark theme. For light-only apps (white, light blue or grey pages) open the app in Apps and switch on Force dark there; add Pure black for #000000. For readers and Notes use Dark pages, only the pages.", fontSize = 13.sp, color = dim, modifier = Modifier.padding(top = 4.dp))
                Text("Force dark: $fd apps · Only the pages: $pg apps", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        PrivacyNote()
    }
}

/** In an app's page: apply Dark pages (white pages to your colour, text to white) only while this app is open. Good for Samsung Notes PDFs. */
@Composable
fun PagesAppCard(pkg: String, mode: Privilege.Mode) {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("blackout", Context.MODE_PRIVATE) }
    var apps by remember { mutableStateOf(sp.getStringSet("pages_apps", emptySet()) ?: emptySet()) }
    val scope = rememberCoroutineScope()
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dark pages", Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Switch(pkg in apps, { v ->
                    apps = if (v) apps + pkg else apps - pkg; sp.edit().putStringSet("pages_apps", apps).apply()
                    // the watcher is what notices the app opening; with root it can be switched on right here
                    if (v && !AppWatch.enabled(ctx)) scope.launch { withContext(Dispatchers.IO) { AppWatch.enableWithPower() } }
                }, enabled = mode != Privilege.Mode.None)
            }
            if (mode == Privilege.Mode.None) Text("Needs root or Shizuku.", fontSize = 13.sp, color = Color(0xFFFFB74D))
            if (pkg in apps && !AppWatch.enabled(ctx)) { Spacer(Modifier.height(6.dp)); AccessRow(mode) }
        }
    }
}

/** In an app's page: Android's own colour inversion while this app is open. Blunt (photos turn negative) but it works on every rooted phone, whatever the app draws with. */
@Composable
fun InvertAppCard(pkg: String, mode: Privilege.Mode) {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("blackout", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf(sp.getStringSet("inv_apps", emptySet()) ?: emptySet()) }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(vertical = 6.dp)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Invert", Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Switch(pkg in apps, { v ->
                apps = if (v) apps + pkg else apps - pkg; sp.edit().putStringSet("inv_apps", apps).apply()
                if (v && !AppWatch.enabled(ctx)) scope.launch { withContext(Dispatchers.IO) { AppWatch.enableWithPower() } }
            }, enabled = mode != Privilege.Mode.None)
        }
    }
}

/** Per-app effects need the accessibility watcher: say so on the home screen when one is on and the watcher is off. */
@Composable
fun WatcherReminder(sp: SharedPreferences, mode: Privilege.Mode) {
    val ctx = LocalContext.current
    fun set(k: String) = !(sp.getStringSet(k, emptySet()) ?: emptySet()).isEmpty()
    val needs = sp.getBoolean("ly_on", false) || set("ly_apps") || set("pages_apps") || set("crush_apps")
    var on by remember { mutableStateOf(AppWatch.enabled(ctx)) }
    LaunchedEffect(needs) { on = AppWatch.enabled(ctx) }
    if (!needs || on) return
    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF2A1F0E)) {
        Column(Modifier.padding(14.dp)) {
            Text("The watcher is off", fontWeight = FontWeight.Bold, color = Color(0xFFFFB74D))
            Text("You turned on effects for apps (top layer, dark pages or black level) but they only work while the accessibility watcher runs.", fontSize = 14.sp, color = Color(0xFFE0C9A0))
            AccessRow(mode)
        }
    }
}

/** Samsung Notes style apps: the list's small previews are drawn through Android's Canvas (the LSPosed hooks reach them), the note itself is drawn by the app's own engine and is not. Inside a note the whole-screen page colours are used instead. */
@Composable
fun EditorAppCard(pkg: String, mode: Privilege.Mode) {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("blackout", Context.MODE_PRIVATE) }
    var apps by remember { mutableStateOf(sp.getStringSet("editor_apps", emptySet()) ?: emptySet()) }
    val scope = rememberCoroutineScope()
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Dark inside a note", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    Text("On the list screen the LSPosed page swap works. Open a note and the page colours (Dark pages) switch on for that screen only, then off again when you go back. Toolbars may look light inside a note.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(pkg in apps, { v ->
                    apps = if (v) apps + pkg else apps - pkg; sp.edit().putStringSet("editor_apps", apps).apply()
                    if (v && !AppWatch.enabled(ctx)) scope.launch { withContext(Dispatchers.IO) { AppWatch.enableWithPower() } }
                }, enabled = mode != Privilege.Mode.None)
            }
            if (mode == Privilege.Mode.None) Text("Needs root or Shizuku.", fontSize = 13.sp, color = Color(0xFFFFB74D))
        }
    }
}
