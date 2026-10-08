package com.umutk.blackout

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.border
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Accent = Color(0xFF8C9EFF)
private val Scheme = darkColorScheme(
    primary = Accent, onPrimary = Color.Black, background = Color.Black, surface = Color.Black,
    surfaceContainer = Color(0xFF0E0E10), surfaceContainerHigh = Color(0xFF16161A), onSurface = Color(0xFFECECF1), onSurfaceVariant = Color(0xFF9A9AA6),
)

class AppRow(val pkg: String, val label: String, val sourceDir: String, val launch: Boolean = true)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme(colorScheme = Scheme) { Surface(Modifier.fillMaxSize(), color = Color.Black) { Root() } } }
    }
}

private fun samsungApp(pkg: String) = pkg.startsWith("com.samsung.") || pkg.startsWith("com.sec.android") || pkg.startsWith("com.sec.")

private fun googleApp(pkg: String) = pkg.startsWith("com.google.") || pkg == "com.android.vending" || pkg == "com.android.chrome" || pkg.startsWith("com.android.vending")

/** Colours that should stay as they are: outlines, dividers, shadows, scrims, ripples, text. */
private val KEEP = Regex("outline|divider|stroke|border|scrim|shadow|ripple|text|icon|ink|hint|disabled|inverse|light|on_|_on", RegexOption.IGNORE_CASE)

@Composable
private fun Root() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("blackout", Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(Privilege.mode) }
    var group by remember { mutableStateOf("all") }   // all / google / samsung
    var query by remember { mutableStateOf("") }
    var limit by remember { mutableFloatStateOf(prefs.getFloat("limit", 56f)) }   // brightest grey that still counts as a dark surface
    var tint by remember { mutableStateOf(prefs.getInt("tint", 0)) }
    var keep by remember { mutableFloatStateOf(prefs.getFloat("keep", 0f)) }      // % of the original brightness that stays (0 = pure black)
    var apps by remember { mutableStateOf<List<AppRow>>(emptyList()) }
    var open by remember { mutableStateOf<AppRow?>(null) }
    val results = remember { mutableStateMapOf<String, ScanResult>() }
    val errors = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(Unit) {
        mode = withContext(Dispatchers.IO) { Privilege.detect(prefs.getString("prefer", "auto") ?: "auto") }
        apps =withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.getInstalledApplications(0)
                .map { AppRow(it.packageName, pm.getApplicationLabel(it).toString(), it.sourceDir, pm.getLaunchIntentForPackage(it.packageName) != null) }.sortedBy { it.label.lowercase() }
        }
    }
    var page by rememberSaveable { mutableStateOf("home") }   // home, global, apps, settings
    var cat by rememberSaveable { mutableStateOf("") }        // category open inside Settings
    BackHandler(open != null || page != "home") { if (open != null) open = null else if (page == "settings" && cat.isNotEmpty()) cat = "" else page = "home" }

    val a = open
    if (a != null) {
        Detail(a, results[a.pkg], errors[a.pkg], limit.toInt(), keep / 100f, mode,
            onScan = {
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { Arsc.scan(a.sourceDir, limit.toInt()) } }
                    r.onSuccess { results[a.pkg] = it; errors.remove(a.pkg) }.onFailure { errors[a.pkg] = it.message ?: it.javaClass.simpleName }
                }
            }, onBack = { open = null })
        return
    }

    // every app you can open, plus the Google and Samsung system ones; Google first, then Samsung, then the rest
    val shown = remember(apps, group, query) {
        apps.filter { (it.launch || googleApp(it.pkg) || samsungApp(it.pkg)) &&
            (group == "all" || (group == "google" && googleApp(it.pkg)) || (group == "samsung" && samsungApp(it.pkg))) &&
            (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true)) }
            .sortedWith(compareBy<AppRow>({ if (googleApp(it.pkg)) 0 else if (samsungApp(it.pkg)) 1 else 2 }, { it.label.lowercase() }))
    }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.weight(1f)) { Column(Modifier.fillMaxSize()) {
        when (page) {
            "colours" -> {
                TopBar("Colours") { page = "home" }
                Column(Modifier.verticalScroll(rememberScrollState())) { ColoursPage(apps, prefs, ctx) }
            }
            "module" -> {
                TopBar("LSPosed module") { page = "home" }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { ModulePage(mode, prefs) }
            }
            "pages" -> {
                TopBar("Dark pages") { page = "home" }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { PageCard(mode, prefs) }
            }
            "notes" -> {
                TopBar("Samsung Notes") { page = "home" }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PagesModCard("com.samsung.android.app.notes", mode)
                    EditorAppCard("com.samsung.android.app.notes", mode)
                    PageCard(mode, prefs, showMaster = false)
                    PagesAppCard("com.samsung.android.app.notes", mode)
                    InvertAppCard("com.samsung.android.app.notes", mode)
                }
            }
            "sysfd" -> {
                TopBar("Force dark") { page = "home" }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { SystemForceDarkCard(mode, prefs) }
            }
            "global" -> {
                TopBar("All Material You apps") { page = "home" }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { GlobalCard(mode, keep / 100f) }
            }
            "apps" -> {
                TopBar("Apps") { page = "home" }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search apps") }, leadingIcon = { Icon(Icons.Filled.Search, null) }, shape = RoundedCornerShape(26.dp))
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("all" to "All apps", "google" to "Google", "samsung" to "Samsung").forEach { (k, n) -> FilterChip(group == k, { group = k }, { Text(n) }) }
                        }
                    }
                    items(shown, key = { it.pkg }) { app ->
                        val r = results[app.pkg]
                        Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { open = app }, color = MaterialTheme.colorScheme.surfaceContainer) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                AppIcon(app.pkg)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(app.pkg, fontSize = 13.sp, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(when { r == null -> "not scanned"; r.namesStripped -> "names stripped"; else -> "${r.candidates.size} greys" }, fontSize = 13.sp, color = if (r?.namesStripped == true) Color(0xFFFFB74D) else Accent)
                            }
                        }
                    }
                    if (shown.isEmpty()) item { Text("No matching apps.", color = dim) }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
            "settings" -> {
                TopBar(when (cat) { "surface" -> "Colours"; "access" -> "Root access"; "diag" -> "Help"; "about" -> "About"; else -> "Settings" }) { if (cat.isEmpty()) page = "home" else cat = "" }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (cat) {
                        "surface" -> Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Which greys count as surfaces", fontSize = 14.sp)
                                GreySlider(limit, { limit = it }) { prefs.edit().putFloat("limit", limit).apply() }
                                Text(if (keep < 1f) "Result: pure black (#000000)" else "Result: ${keep.toInt()}% of the original brightness stays", fontSize = 14.sp)
                                Slider(keep, { keep = it }, valueRange = 0f..40f, onValueChangeFinished = { prefs.edit().putFloat("keep", keep).apply() })
                                TintPicker(tint) { tint = it; prefs.edit().putInt("tint", it).apply() }
                                GreyPreview(limit, keep, tint)
                                Text("Higher limit = also darkens lighter greys (cards, sheets). Re-scan an app after changing it.", color = dim, fontSize = 13.sp)
                            }
                        }
                        "access" -> PrivilegeCard(mode,
                            onRoot = { prefs.edit().putString("prefer", "root").apply(); scope.launch { mode = withContext(Dispatchers.IO) { Privilege.detect("root") } } },
                            onCheck = { prefs.edit().putString("prefer", "auto").apply(); scope.launch { mode = withContext(Dispatchers.IO) { Privilege.detect() } } },
                            onShizuku = { Privilege.requestShizuku(); prefs.edit().putString("prefer", "shizuku").apply(); scope.launch { kotlinx.coroutines.delay(1500); mode = withContext(Dispatchers.IO) { Privilege.detect("shizuku") } } })
                        "diag" -> {
                            var text by remember { mutableStateOf("") }
                            var busy by remember { mutableStateOf(false) }
                            Text("Shows what this phone answers to the overlay commands. Send it along when something does not work.", color = dim, fontSize = 13.sp)
                            Button({ busy = true; scope.launch { text = withContext(Dispatchers.IO) { Overlays.diagnose() }; busy = false } }, enabled = mode != Privilege.Mode.None && !busy) { Text("Run diagnostics") }
                            if (mode == Privilege.Mode.None) Text("Needs root or Shizuku first (Settings > Access).", color = Color(0xFFFFB74D), fontSize = 13.sp)
                            if (text.isNotBlank()) Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) { Text(text, Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
                        }
                        "about" -> AboutPage()
                        else -> {
                            SetRow(Icons.Filled.Tune, "Colours", "Grey limit ${limit.toInt()}, keep ${keep.toInt()}%") { cat = "surface" }
                            SetRow(Icons.Filled.Security, "Root access", if (mode == Privilege.Mode.None) "Not available" else "Using ${Privilege.detail}") { cat = "access" }
                            SetRow(Icons.Filled.BugReport, "Help", "See why something does not work") { cat = "diag" }
                            SetRow(Icons.Filled.Info, "About", "BlackOut 0.14") { cat = "about" }
                        }
                    }
                }
            }
            else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MoonLogo(Modifier.size(52.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("BlackOut", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Accent)
                        Text("Grey surfaces → AMOLED black", color = dim, fontSize = 14.sp)
                    }
                }
                Row(Modifier.clip(RoundedCornerShape(20.dp)).clickable { page = "settings"; cat = "access" }.background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(if (mode != Privilege.Mode.None) Color(0xFF4CAF50) else Color(0xFFF44336)))
                    Spacer(Modifier.width(8.dp))
                    Text(if (mode == Privilege.Mode.None) "No root or Shizuku" else "Ready · ${Privilege.detail}", fontSize = 14.sp)
                }
                WatcherReminder(prefs, mode)
                SetRow(Icons.Filled.DarkMode, "Dark pages", "White pages", big = true, badge = if (prefs.getBoolean("pg_on", false)) "ON" else "OFF") { page = "pages" }
                SetRow(Icons.Filled.Extension, "LSPosed module", "Black apps", big = true, badge = if (Status.isActive()) "ON" else null) { page = "module" }
                SetRow(Icons.Filled.InvertColors, "Force dark", "Light apps", big = true, badge = if (prefs.getBoolean("fdsys_on", false)) "ON" else "OFF") { page = "sysfd" }
                SetRow(Icons.Filled.AutoAwesome, "Google apps", "Material You", big = true) { page = "global" }
                SetRow(Icons.Filled.Apps, "Apps", "${apps.count { it.launch }}", big = true) { page = "apps" }
                apps.firstOrNull { it.pkg == "com.samsung.android.app.notes" }?.let { n ->
                    SetRow(Icons.Filled.EditNote, "Samsung Notes", "Pages", big = true) { page = "notes" }
                }
                SetRow(Icons.Filled.Settings, "Settings", "", big = true) { page = "settings"; cat = "" }
                PrivacyNote()
            }
        }
        } }
        NavigationBar(containerColor = Color(0xFF0A0A0C)) {
            listOf(Triple("home", "Home", Icons.Filled.Home), Triple("apps", "Apps", Icons.Filled.Apps), Triple("colours", "Colours", Icons.Filled.Palette), Triple("settings", "Settings", Icons.Filled.Settings)).forEach { (k, n, ic) ->
                NavigationBarItem(page == k || (k == "home" && page !in setOf("apps", "colours", "settings")), { page = k; cat = "" }, { Icon(ic, n) }, label = { Text(n) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.Black, indicatorColor = Accent, selectedTextColor = Accent, unselectedIconColor = Color(0xFF9A9AA6), unselectedTextColor = Color(0xFF9A9AA6)))
            }
        }
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

/** A category row with an icon, used on the home screen and in Settings. */
@Composable
private fun SetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, big: Boolean = false, badge: String? = null, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(if (big) 18.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(if (big) 48.dp else 40.dp).clip(CircleShape).background(Accent.copy(alpha = .15f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Accent) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = if (big) 18.sp else 16.sp)
                Text(sub, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (badge != null) Text(badge, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (badge == "ON") Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x22FFFFFF)).padding(horizontal = 8.dp, vertical = 3.dp))
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The app logo: an outlined crescent moon (Material "dark mode" shape) with two small stars. */
@Composable
private fun MoonLogo(modifier: Modifier) {
    val path = remember { androidx.compose.ui.graphics.vector.PathParser().parsePathString("M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9s9,-4.03 9,-9c0,-0.46 -0.04,-0.92 -0.1,-1.36c-0.98,1.37 -2.58,2.26 -4.4,2.26c-2.98,0 -5.4,-2.42 -5.4,-5.4c0,-1.81 0.89,-3.42 2.26,-4.4C12.92,3.04 12.46,3 12,3z").toPath() }
    androidx.compose.foundation.Canvas(modifier) {
        val sc = size.width / 24f
        scale(sc, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(path, Accent, style = androidx.compose.ui.graphics.drawscope.Stroke(1.3f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            drawCircle(Accent, 0.6f, androidx.compose.ui.geometry.Offset(18.5f, 5f)); drawCircle(Accent, 0.4f, androidx.compose.ui.geometry.Offset(21f, 8.5f))
        }
    }
}

@Composable
private fun PrivilegeCard(mode: Privilege.Mode, onRoot: () -> Unit, onCheck: () -> Unit, onShizuku: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (mode != Privilege.Mode.None) Color(0xFF4CAF50) else Color(0xFFF44336)))
                Spacer(Modifier.width(10.dp))
                Text(if (mode == Privilege.Mode.None) "No root / Shizuku access" else "Ready: using ${Privilege.detail}", fontWeight = FontWeight.Medium)
            }
            if (mode == Privilege.Mode.None) {
                Text("Overlays can only be created by root or by the shell user. Grant root to BlackOut, or start Shizuku and allow BlackOut in it.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                if (Privilege.detail.isNotBlank()) Text(Privilege.detail, fontSize = 13.sp, color = Color(0xFFFFB74D), modifier = Modifier.padding(top = 6.dp))
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onRoot) { Text("Use root") }
                OutlinedButton(onShizuku) { Text("Use Shizuku") }
                OutlinedButton(onCheck) { Text("Auto") }
            }
        }
    }
}

@Composable
private fun AppIcon(pkg: String) {
    val ctx = LocalContext.current
    val bmp: ImageBitmap? = remember(pkg) { runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
    if (bmp != null) Image(bmp, null, Modifier.size(40.dp)) else Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF222226)))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Detail(app: AppRow, result: ScanResult?, error: String?, limit: Int, keep: Float, mode: Privilege.Mode, onScan: () -> Unit, onBack: () -> Unit) {
    val tintCtx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var active by remember { mutableIntStateOf(-1) }
    var progress by remember { mutableStateOf("") }
    val off = remember(app.pkg) { mutableStateListOf<String>() }                // names the user switched off
    LaunchedEffect(app.pkg) { if (result == null) onScan() }
    LaunchedEffect(app.pkg, mode) { if (mode != Privilege.Mode.None) active = withContext(Dispatchers.IO) { Overlays.activeCount(app.pkg) } }
    LaunchedEffect(result) { result?.candidates?.forEach { if (KEEP.containsMatchIn(it.name) && it.name !in off) off.add(it.name) } }

    val chosen = result?.candidates?.filter { it.name !in off }.orEmpty()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            AppIcon(app.pkg)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text(app.label, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1); Text(app.pkg, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); if (progress.isNotEmpty()) Text(progress, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                if (error != null) Text("Scan failed: $error", color = Color(0xFFF44336))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val spx = LocalContext.current.getSharedPreferences("blackout", Context.MODE_PRIVATE)
                    ForceDarkCard(app.pkg, mode)
                    PagesModCard(app.pkg, mode)
                    PagesAppCard(app.pkg, mode)
                    InvertAppCard(app.pkg, mode)
                    RecolorCard(app.pkg, spx)
                }
                if (result != null) {
                    if (result.namesStripped) CrushCard(app.pkg, mode)
                    if (active >= 0) Text(if (active > 0) "$active overlays are active now" else "No BlackOut overlay active", fontSize = 13.sp, color = if (active > 0) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({
                        busy = true; progress = "Starting…"
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { Overlays.apply(app.pkg, chosen.map { it.name to Overlays.scaled(it.color, keep, tintCtx.getSharedPreferences("blackout", android.content.Context.MODE_PRIVATE).getInt("tint", 0)) }) { d, n -> progress = "Applying $d / $n" } }
                            msg = r.message; busy = false; progress = ""
                            if (r.ok) { active = withContext(Dispatchers.IO) { Overlays.activeCount(app.pkg) } }
                        }
                    }, enabled = mode != Privilege.Mode.None && chosen.isNotEmpty() && !busy && result?.namesStripped != true) { Text("Apply (${chosen.size})") }
                    OutlinedButton({
                        busy = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { Overlays.remove(app.pkg) }
                            msg = r.message; busy = false; active = withContext(Dispatchers.IO) { Overlays.activeCount(app.pkg) }
                        }
                    }, enabled = mode != Privilege.Mode.None && !busy) { Text("Remove") }
                    OutlinedButton({ scope.launch { withContext(Dispatchers.IO) { Overlays.forceStop(app.pkg) }; msg = "Stopped. Open the app again." } }, enabled = mode != Privilege.Mode.None) { Text("Restart app") }
                }
                msg?.let { Text(it, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ off.clear(); result?.candidates?.forEach { if (KEEP.containsMatchIn(it.name)) off.add(it.name) } }) { Text("Surfaces only") }
                    TextButton({ off.clear() }) { Text("Select all") }
                    TextButton({ off.clear(); result?.candidates?.forEach { off.add(it.name) } }) { Text("Select none") }
                    TextButton(onScan) { Text("Re-scan") }
                }
            }
            if (result == null && error == null) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Reading the app's colours…") } }
            items(result?.candidates.orEmpty(), key = { it.id }) { c ->
                val on = c.name !in off
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { if (on) off.add(c.name) else off.remove(c.name) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, { if (on) off.add(c.name) else off.remove(c.name) })
                    Swatch(c.color); Text("→", Modifier.padding(horizontal = 6.dp)); Swatch(Overlays.scaled(c.color, keep, LocalContext.current.getSharedPreferences("blackout", android.content.Context.MODE_PRIVATE).getInt("tint", 0)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item { AllColoursCard(app, mode) }
        }
    }
}

/** One switch for every Material You app: the dark tones of the system palette (the greys Google apps take their surfaces from) become black. */
@Composable
private fun GlobalCard(mode: Privilege.Mode, keep: Float) {
    val scope = rememberCoroutineScope()
    var tinted by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var active by remember { mutableIntStateOf(-1) }
    LaunchedEffect(mode) { if (mode != Privilege.Mode.None) active = withContext(Dispatchers.IO) { Overlays.activeCount("android") } }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp)) {
            Text("All Material You apps at once", fontWeight = FontWeight.Bold)
            Text("Play Store, Gmail, Photos, Clock, Settings and others take their dark greys from Android's colour palette. Turning those tones black blackens them all, without scanning each app.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Also tinted containers (chips, cards)", Modifier.weight(1f), fontSize = 13.sp)
                Switch(tinted, { tinted = it })
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { Overlays.apply("android", Overlays.globalNames(tinted).map { it to Overlays.scaled(Overlays.GLOBAL_BASE, keep) }) }
                        msg = r.message; busy = false; active = withContext(Dispatchers.IO) { Overlays.activeCount("android") }
                    }
                }, enabled = mode != Privilege.Mode.None && !busy) { Text("Apply to all") }
                OutlinedButton({
                    busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { Overlays.remove("android") }
                        msg = r.message; busy = false; active = withContext(Dispatchers.IO) { Overlays.activeCount("android") }
                    }
                }, enabled = mode != Privilege.Mode.None && !busy) { Text("Remove") }
            }
            if (active >= 0) Text(if (active > 0) "$active system tones are black now" else "Not active", fontSize = 13.sp, color = if (active > 0) Accent else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            msg?.let { Text(it, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
            Text("Afterwards close the apps (recents) and open them again. If a Google app stays grey it does not use the system palette: use the per-app list below.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun Swatch(argb: Int) = Box(Modifier.size(26.dp).clip(CircleShape).background(Color(argb)).border(1.dp, Color(0x44FFFFFF), CircleShape))
