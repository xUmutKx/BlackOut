package com.umutk.blackout

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** In an app's page: make a light-only app dark (LSPosed: Android's force dark + a pure-black repaint), with a live view of what the module is doing in it. */
@Composable
fun ForceDarkCard(pkg: String, mode: Privilege.Mode) {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("blackout", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var fd by remember { mutableStateOf(sp.getStringSet("fd_apps", emptySet()) ?: emptySet()) }
    var crush by remember { mutableStateOf(sp.getStringSet("crush_apps", emptySet()) ?: emptySet()) }
    var smart by remember { mutableStateOf(sp.getStringSet("smart_apps", emptySet()) ?: emptySet()) }
    var adv by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf(HookReport.read(ctx, pkg)) }
    LaunchedEffect(pkg) { while (true) { report = HookReport.read(ctx, pkg); kotlinx.coroutines.delay(3000) } }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    fun setBoth(v: Boolean) {
        fd = if (v) fd + pkg else fd - pkg; smart = if (v) smart + pkg else smart - pkg
        sp.edit().putStringSet("fd_apps", fd).putStringSet("smart_apps", smart).apply()
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Make this app dark (LSPosed)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("For apps with only a light theme. Android's force dark turns pages dark and text light and leaves photos alone; on top of it the module paints big white, light blue and grey areas pure black and turns dark text light. Tick this app under BlackOut in LSPosed too.", fontSize = 13.sp, color = dim)
                }
                Switch(pkg in fd && pkg in smart, { setBoth(it) })
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Pure black on top", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text("Android's dark is about #1C1C1C. While this app is open, root lowers the black level so that grey becomes #000000. Needs root and the watcher.", fontSize = 13.sp, color = dim)
                }
                Switch(pkg in crush, { v ->
                    crush = if (v) crush + pkg else crush - pkg
                    val e = sp.edit().putStringSet("crush_apps", crush)
                    if (v && sp.getInt("crush_level", 30) < 28) e.putInt("crush_level", 30)
                    e.apply()
                    if (v && !AppWatch.enabled(ctx)) scope.launch { withContext(Dispatchers.IO) { AppWatch.enableWithPower() } }
                }, enabled = mode != Privilege.Mode.None)
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Takes effect when the app starts again.", Modifier.weight(1f), fontSize = 13.sp, color = dim)
                OutlinedButton({ scope.launch { withContext(Dispatchers.IO) { Overlays.forceStop(pkg) }; msg = "Stopped. Open the app again and wait a few seconds." } }, enabled = mode != Privilege.Mode.None) { Text("Restart app") }
            }
            HookStatusBlock(report, pkg in fd || pkg in smart)
            if (report != null && report!!.keys == 0) OutlinedButton({
                scope.launch {
                    val o = withContext(Dispatchers.IO) { Privilege.run("chmod 755 /data/data/${ctx.packageName}; chmod 755 /data/data/${ctx.packageName}/shared_prefs; chmod 644 /data/data/${ctx.packageName}/shared_prefs/blackout.xml; echo ok") }
                    msg = if (o.text.contains("ok")) "Settings are readable now. Restart the app." else "Failed: ${o.text.take(100)}"
                }
            }, enabled = mode != Privilege.Mode.None) { Text("Fix settings access (root)") }
            msg?.let { Text(it, fontSize = 13.sp) }
            TextButton({ adv = !adv }) { Text(if (adv) "Hide the two parts" else "Use the two parts separately") }
            if (adv) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Force dark only", fontSize = 14.sp); Text("Android's engine, about #1C1C1C.", fontSize = 13.sp, color = dim) }
                    Switch(pkg in fd, { v -> fd = if (v) fd + pkg else fd - pkg; sp.edit().putStringSet("fd_apps", fd).apply() })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Pure black repaint only", fontSize = 14.sp); Text("Without force dark, a light spot it misses stays light under light text.", fontSize = 13.sp, color = dim) }
                    Switch(pkg in smart, { v -> smart = if (v) smart + pkg else smart - pkg; sp.edit().putStringSet("smart_apps", smart).apply() })
                }
            }
        }
    }
}

/** What the module reported from inside the app: is it there, can it read the settings, and did it change anything. */
@Composable
private fun HookStatusBlock(r: HookReport?, wanted: Boolean) {
    val ok = Color(0xFF4CAF50); val warn = Color(0xFFFFB74D); val bad = Color(0xFFF44336)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(top = 8.dp)) {
        if (r == null) {
            Text("Module not seen in this app yet", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = warn)
            Text("In LSPosed open Modules > BlackOut and tick this app. Then press Restart app, open it and wait 5 seconds.", fontSize = 13.sp, color = dim)
            return
        }
        val age = (System.currentTimeMillis() - r.at) / 1000
        val fresh = age < 40
        Text(if (fresh) "Module is running in this app" else "Module was running here ${age / 60} min ago (the app is closed now)", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (fresh) ok else dim)
        if (r.keys == 0) Text("It cannot read BlackOut's settings, so nothing you switch on here reaches it.", fontSize = 13.sp, color = bad)
        else if (wanted && !r.fd && !r.smart) Text("This app was started before you switched it on. Press Restart app.", fontSize = 13.sp, color = warn)
        Text("Android ${r.sdk} · settings read: ${if (r.keys < 0) "unknown" else r.keys.toString()}", fontSize = 13.sp, color = dim)
        if (r.fd) Text("Force dark switched on in ${r.forced} window${if (r.forced == 1) "" else "s"}" + if (r.forced == 0) " (none yet: open a screen of the app)" else "", fontSize = 13.sp, color = if (r.forced > 0) ok else warn)
        if (r.smart || r.pages) Text("Repainted ${r.fills} light areas, turned ${r.texts} dark texts light" + if (r.fills == 0 && r.texts == 0) " (nothing yet: the app may draw with its own engine)" else "", fontSize = 13.sp, color = if (r.fills + r.texts > 0) ok else warn)
        Text("Dark greys turned black: ${r.greys}", fontSize = 13.sp, color = dim)
    }
}

/** Home page: Android's own "Override force-dark" (developer options) through root, for every app that has a light theme. */
@Composable
fun SystemForceDarkCard(mode: Privilege.Mode, sp: android.content.SharedPreferences) {
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(sp.getBoolean("fdsys_on", false)) }
    var now by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    fun read() { scope.launch { now = withContext(Dispatchers.IO) { Privilege.run("getprop debug.hwui.force_dark").text.trim().ifBlank { "(not set)" } } } }
    LaunchedEffect(mode) { if (mode != Privilege.Mode.None) read() }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (on) "Force dark is ON (all light apps)" else "Force dark is off", fontWeight = FontWeight.Bold)
                    Text("The same as Developer options > Override force-dark: every app with a light theme gets Android's dark rendering. Pages turn dark, text turns light, photos stay. Needs root. No LSPosed.", fontSize = 13.sp, color = dim)
                }
                Switch(on, { v ->
                    on = v; sp.edit().putBoolean("fdsys_on", v).apply()
                    scope.launch {
                        val o = withContext(Dispatchers.IO) { Privilege.run("setprop debug.hwui.force_dark ${if (v) "true" else "false"}; getprop debug.hwui.force_dark") }
                        now = o.text.trim().ifBlank { "(not set)" }
                        msg = if (o.text.trim() == (if (v) "true" else "false")) "Done. Apps pick it up when they start: close them in recents and open them again." else "The phone did not take it: ${o.text.take(120)}"
                    }
                }, enabled = mode != Privilege.Mode.None)
            }
            if (mode == Privilege.Mode.None) Text("Needs root first (Settings > Root access).", fontSize = 13.sp, color = Color(0xFFFFB74D), modifier = Modifier.padding(top = 4.dp))
            now?.let { Text("Android says debug.hwui.force_dark = $it", fontSize = 13.sp, color = dim, modifier = Modifier.padding(top = 6.dp)) }
            msg?.let { Text(it, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
            Text("Android's dark is about #1C1C1C. For pure black switch on Pure black on top in an app's page. It lasts until the phone restarts and is switched on again by itself. Apps that opt out of force dark need the per-app way (Apps > the app > Make this app dark).", fontSize = 13.sp, color = dim, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** In an app's page: only the pages inside this app swap light and dark (LSPosed), the rest of the app stays as it is. */
@Composable
fun PagesModCard(pkg: String, mode: Privilege.Mode) {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("blackout", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf(sp.getStringSet("pgmod_apps", emptySet()) ?: emptySet()) }
    var msg by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf(HookReport.read(ctx, pkg)) }
    LaunchedEffect(pkg) { while (true) { report = HookReport.read(ctx, pkg); kotlinx.coroutines.delay(3000) } }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Dark pages, only the pages (LSPosed)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Inside this app only: big light pictures (PDF pages, paper) and big white areas swap light and dark with the Dark pages colours. Toolbars, menus and icons stay as they are. Tick this app in LSPosed too.", fontSize = 13.sp, color = dim)
                }
                Switch(pkg in apps, { v -> apps = if (v) apps + pkg else apps - pkg; sp.edit().putStringSet("pgmod_apps", apps).apply() })
            }
            Text("Reaches what the app draws through Android. A page the app paints with its own graphics engine stays as it is.", fontSize = 13.sp, color = dim, modifier = Modifier.padding(top = 6.dp))
            HookStatusBlock(report, pkg in apps)
            PageViewPicker(pkg, mode)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Takes effect when the app starts again.", Modifier.weight(1f), fontSize = 13.sp, color = dim)
                OutlinedButton({ scope.launch { withContext(Dispatchers.IO) { Overlays.forceStop(pkg) }; msg = "Stopped. Open the app again." } }, enabled = mode != Privilege.Mode.None) { Text("Restart app") }
            }
            msg?.let { Text(it, fontSize = 13.sp) }
        }
    }
}

/** What the grey limit means, in colours: a strip of greys (top: what an app asks for, bottom: what it gets) and a small dark app before and after. */
@Composable
fun GreyPreview(limit: Float, keep: Float, tint: Int = 0) {
    val l = limit.toInt(); val k = keep / 100f
    var look by remember { mutableStateOf(0) }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) {
            for (v in 16..112 step 8) {
                val c = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                val out = Grey.fix(c, l, k, tint)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(24.dp).background(Color(c)))
                    Box(Modifier.fillMaxWidth().height(24.dp).background(Color(out)))
                    Box(Modifier.padding(top = 3.dp).size(5.dp).clip(CircleShape).background(if (out != c) Color(0xFF8C9EFF) else Color.Transparent))
                }
            }
        }
        Text("Top row: the grey an app asks for. Bottom row: what it gets. A dot marks every grey that changes.", fontSize = 13.sp, color = dim)
        Row(Modifier.padding(top = 10.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MockLooks.forEachIndexed { i, lk -> FilterChip(look == i, { look = i }, { Text(lk.name) }) }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MockDarkApp(null, Modifier.weight(1f), MockLooks[look])
            MockDarkApp({ Grey.fix(it, l, k, tint) }, Modifier.weight(1f), MockLooks[look])
        }
        Text("Pick an app look. Left: before. Right: the same app with this setting.", fontSize = 13.sp, color = dim, modifier = Modifier.padding(top = 4.dp))
    }
}

/** What the black level does: every tone gets [level] darker (the same shift PageDark.applyCrush hands to the screen). */
@Composable
fun LevelPreview(level: Float) {
    val l = level.toInt()
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) {
            for (v in 0..144 step 12) {
                val o = (v - l).coerceAtLeast(0)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(24.dp).background(Color(0xFF000000.toInt() or (v shl 16) or (v shl 8) or v)))
                    Box(Modifier.fillMaxWidth().height(24.dp).background(Color(0xFF000000.toInt() or (o shl 16) or (o shl 8) or o)))
                    Box(Modifier.padding(top = 3.dp).size(5.dp).clip(CircleShape).background(if (o == 0 && v > 0) Color(0xFF8C9EFF) else Color.Transparent))
                }
            }
        }
        Text("Top row: the screen now. Bottom row: with this level. A dot marks the greys that become pure black.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Dark greys of well-known app styles for the preview: page, top bar, card, sheet, chip. */
class MockLook(val name: String, val greys: LongArray)
val MockLooks = listOf(
    MockLook("Material", longArrayOf(0xFF121212, 0xFF1F1F1F, 0xFF2C2C2C, 0xFF424242, 0xFF5A5A5A)),
    MockLook("Play Store", longArrayOf(0xFF131314, 0xFF1E1F20, 0xFF282A2C, 0xFF333537, 0xFF444746)),
    MockLook("Chat app", longArrayOf(0xFF0B141A, 0xFF1F2C34, 0xFF202C33, 0xFF2A3942, 0xFF3B4A54)),
    MockLook("One UI", longArrayOf(0xFF000000, 0xFF141414, 0xFF252525, 0xFF2F2F2F, 0xFF444444)),
    MockLook("Telegram", longArrayOf(0xFF0E1621, 0xFF17212B, 0xFF1D2733, 0xFF242F3D, 0xFF2B5278)),
)

/** A tiny dark-theme app in the greys apps commonly use, optionally pushed through [fix]. */
@Composable
private fun MockDarkApp(fix: ((Int) -> Int)?, modifier: Modifier, look: MockLook = MockLooks[0]) {
    fun c(v: Long) = Color(fix?.invoke(v.toInt()) ?: v.toInt())
    val pal = look.greys
    Canvas(modifier.height(170.dp).clip(RoundedCornerShape(10.dp))) {
        val w = size.width; val h = size.height
        drawRect(c(pal[0]))                                                   // page
        drawRect(c(pal[1]), size = Size(w, h * .13f))                        // top bar
        drawRect(Color(0xFFE6E6E6), Offset(w * .08f, h * .045f), Size(w * .40f, h * .04f))
        for (i in 0..1) {
            val y = h * (.19f + i * .24f)
            drawRoundRect(c(pal[2]), Offset(w * .06f, y), Size(w * .88f, h * .20f), CornerRadius(14f))   // card
            drawRect(Color(0xFFDADADA), Offset(w * .12f, y + h * .05f), Size(w * .50f, h * .03f))
            drawRect(Color(0xFF9A9A9A), Offset(w * .12f, y + h * .11f), Size(w * .70f, h * .025f))
        }
        drawRoundRect(c(pal[3]), Offset(0f, h * .70f), Size(w, h * .30f + 20f), CornerRadius(22f))        // bottom sheet
        drawRoundRect(c(pal[4]), Offset(w * .08f, h * .77f), Size(w * .30f, h * .08f), CornerRadius(30f)) // chip
        drawRoundRect(Color(0xFF8AB4F8), Offset(w * .56f, h * .86f), Size(w * .36f, h * .09f), CornerRadius(30f))
    }
}

/** The grey limit slider with its colour in sight: the track is the grey ramp itself, the label carries a swatch of the chosen grey. */
@Composable
fun GreySlider(limit: Float, onChange: (Float) -> Unit, onDone: () -> Unit) {
    val v = limit.toInt()
    val grey = Color(v / 255f, v / 255f, v / 255f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(grey).border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(6.dp)))
        Text("  up to #%02X%02X%02X".format(v, v, v), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black).border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(6.dp)))
    }
    Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.padding(horizontal = 10.dp).fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(24 / 255f, 24 / 255f, 24 / 255f), Color(96 / 255f, 96 / 255f, 96 / 255f)))))
        Slider(limit, onChange, valueRange = 24f..96f, onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent, thumbColor = Color(0xFF8C9EFF)))
    }
    Text("Left: a dark grey that is nearly black. Right: a lighter grey. Everything from the left up to the chosen swatch becomes black.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** What the dimming layer does to a white page, a dark page and a line of text. */
@Composable
fun LayerPreview(alpha: Float, rgb: Int) {
    val a = alpha / 100f
    val layer = Color(0xFF000000.toInt() or rgb).copy(alpha = a)
    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(0xFFFFFFFF.toInt() to 0xFF222222.toInt(), 0xFF2B2B2B.toInt() to 0xFFEEEEEE.toInt()).forEach { (bg, fg) ->
            Box(Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(10.dp)).background(Color(bg))) {
                Text("Sample text", Modifier.align(Alignment.Center), color = Color(fg), fontSize = 14.sp)
                Box(Modifier.matchParentSize().background(layer))
            }
        }
    }
}

val TintChoices = listOf("Black" to 0x000000, "Navy" to 0x050A18, "Blue" to 0x08142C, "Teal" to 0x051616, "Forest" to 0x07120A, "Plum" to 0x120818, "Warm" to 0x140E08, "Red" to 0x1A0808)

/** Side colour for the "black": instead of #000000 the surfaces become a very dark blue, green, plum... (tint is added after the brightness is scaled). */
@Composable
fun TintPicker(tint: Int, onPick: (Int) -> Unit) {
    Text("Colour of the dark", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TintChoices.forEach { (n, c) ->
            val sel = c == tint
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onPick(c) }) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or c)).border(if (sel) 2.dp else 1.dp, if (sel) Color(0xFF8C9EFF) else Color(0x66FFFFFF), CircleShape))
                Text(n, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
