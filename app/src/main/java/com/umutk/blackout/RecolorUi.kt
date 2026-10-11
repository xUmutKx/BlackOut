package com.umutk.blackout

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun Dot(c: Int, size: Int = 22) = Box(Modifier.size(size.dp).clip(CircleShape).background(Color(c or -0x1000000)).border(1.dp, Color(0x55FFFFFF), CircleShape))

/** Shows the floating bar for [target] ("*" = the app in front). False when accessibility is off, so the user is told instead of nothing happening. */
private fun openBar(ctx: Context, target: String): Boolean {
    val svc = AppWatch.live
    if (svc == null) {
        Toast.makeText(ctx, "Turn on BlackOut in Accessibility first", Toast.LENGTH_LONG).show()
        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return false
    }
    svc.openBar(target)
    return true
}

/** Color rules of one app: what each picked color turns into, how strongly, and a switch that turns all of the app's colour changes off. New ones come from the floating bar or a hex code. */
@Composable
fun RecolorCard(pkg: String, sp: SharedPreferences) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rules by remember(pkg) { mutableStateOf(RecolorRules.get(sp, pkg)) }
    DisposableEffect(pkg) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (k == "rc_$pkg") rules = RecolorRules.get(sp, pkg) }
        sp.registerOnSharedPreferenceChangeListener(l)
        onDispose { sp.unregisterOnSharedPreferenceChangeListener(l) }
    }
    var on by remember(pkg) { mutableStateOf(RecolorRules.enabled(sp, pkg)) }
    var code by remember { mutableStateOf("") }          // source colour, hex
    var to by remember { mutableStateOf(0x000000) }       // target colour
    var strength by remember { mutableFloatStateOf(100f) }
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val refused = Overlays.refused(pkg)
    val src = if (code.length == 6) code.toInt(16) or -0x1000000 else null
    val pct = strength.toInt()
    val blended = RecolorRules.mix(src ?: 0xFFFFFFFF.toInt(), to or -0x1000000, pct)

    // the switch: off removes the app's overlays and stops the rules at its next start; on puts the saved overlays back
    fun setOn(v: Boolean) {
        on = v; sp.edit().putBoolean("rcon_$pkg", v).apply()
        if (Privilege.mode != Privilege.Mode.None && !refused) scope.launch { withContext(Dispatchers.IO) { Overlays.setEnabled(pkg, v) } }
    }

    // the same rules, written as overlays now (root): the colours the app really uses, each one blended the way its rule says
    fun applyNow() {
        busy = true; msg = ""
        scope.launch {
            val (ok, text) = withContext(Dispatchers.IO) {
                val cands = runCatching { Arsc.scan(ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir, all = true).candidates }.getOrDefault(emptyList())
                val items = cands.filter { !it.name.contains("obfuscated", true) }.mapNotNull { c ->
                    rules.firstOrNull { RecolorRules.matches(c.color, it) }?.let { c.name to RecolorRules.mix(c.color, it.to, it.strength) }
                }
                if (items.isEmpty()) return@withContext false to "No color of this app matches the rules."
                val r = Overlays.apply(pkg, items)
                if (r.ok) { items.forEach { (n, col) -> Overlays.saveOverride(sp, pkg, n, col) }; Overlays.forceStop(pkg) }
                r.ok to r.message
            }
            msg = if (ok) "$text The app was restarted." else text
            busy = false
        }
    }

    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // collapsed by default: the header shows how many rules the app has and whether they are on
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Colors (${rules.size})", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
                }
                Switch(on, { setOn(it) }, Modifier.padding(start = 8.dp))
            }
            Text(if (on) "Colour changes are on for this app" else "Off: this app keeps its own colours", fontSize = 12.sp, color = dim)
            // the dropper stays visible even when the list is collapsed
            Button({ openBar(ctx, pkg) }) { Icon(Icons.Filled.Colorize, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Pick on screen") }
            if (open) {
                Text("One color turns into another inside this app. Strength 100% replaces it; lower values blend it towards the target.", fontSize = 13.sp, color = dim)
                rules.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Dot(r.from); Text("→", color = dim); Dot(r.to)
                        Text("#%06X  ±%d%%  %d%%".format(r.from and 0xFFFFFF, r.tol, r.strength), fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        IconButton({ RecolorRules.put(sp, pkg, rules - r); rules = RecolorRules.get(sp, pkg) }, Modifier.size(32.dp)) { Icon(Icons.Filled.Close, "Remove", Modifier.size(18.dp)) }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Black" to 0x000000, "Charcoal" to 0x121212, "Graphite" to 0x1E1F22, "Navy" to 0x0B1220, "Warm" to 0x1A1410, "Forest" to 0x0C1510).forEach { (n, c) ->
                        FilterChip(to == c, { to = c }, { Text(n) }, leadingIcon = { Dot(c, 16) })
                    }
                }
                OutlinedTextField(code, { code = it.removePrefix("#").take(6).uppercase() }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Source color, e.g. FFFFFF") }, shape = RoundedCornerShape(14.dp))
                Text("Strength $pct%", fontSize = 13.sp)
                Slider(strength, { strength = it }, valueRange = 0f..100f)
                // live preview: source, what it becomes at this strength, and the target
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Dot(src ?: 0xFFFFFF, 40); Text("→", fontSize = 20.sp); Dot(blended, 40); Text("→", fontSize = 20.sp, color = dim); Dot(to, 40)
                    Text("#%06X".format(blended and 0xFFFFFF), fontSize = 13.sp, color = dim, fontFamily = FontFamily.Monospace)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ RecolorRules.add(sp, pkg, src ?: 0, to or -0x1000000, 12, pct); code = ""; rules = RecolorRules.get(sp, pkg) },
                        enabled = src != null, modifier = Modifier.weight(1f)) { Text("Save rule") }
                    OutlinedButton({ RecolorRules.add(sp, pkg, 0xFFFFFF or -0x1000000, to or -0x1000000, 6, pct); rules = RecolorRules.get(sp, pkg) },
                        modifier = Modifier.weight(1f)) { Text("White → this") }
                }
                if (!refused) OutlinedButton({ applyNow() }, Modifier.fillMaxWidth(), enabled = Privilege.mode != Privilege.Mode.None && rules.isNotEmpty() && !busy) {
                    Text("Apply now (root)")
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp, color = dim)
            }
        }
    }
}

/** The Colors tab: the apps that have color rules, and the floating bar for the app that is in front. */
@Composable
fun ColorsPage(apps: List<AppRow>, sp: SharedPreferences, ctx: Context) {
    var withRules by remember { mutableStateOf(RecolorRules.apps(sp)) }
    DisposableEffect(Unit) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (k != null && k.startsWith("rc_")) withRules = RecolorRules.apps(sp) }
        sp.registerOnSharedPreferenceChangeListener(l)
        onDispose { sp.unregisterOnSharedPreferenceChangeListener(l) }
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button({
            // the bar appears first, then Home, so the user can open the app to pick from (the bar stays on screen)
            if (openBar(ctx, "*")) ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, Modifier.fillMaxWidth()) { Icon(Icons.Filled.Colorize, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Open the color bar") }
        withRules.forEach { pkg ->
            val name = apps.firstOrNull { it.pkg == pkg }?.label ?: pkg
            Text(name, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
            RecolorCard(pkg, sp)
        }
    }
}
