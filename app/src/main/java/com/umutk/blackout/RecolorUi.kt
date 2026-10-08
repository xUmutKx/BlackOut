package com.umutk.blackout

import android.content.Context
import android.content.SharedPreferences
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun Dot(c: Int, size: Int = 22) = Box(Modifier.size(size.dp).clip(CircleShape).background(Color(c or -0x1000000)).border(1.dp, Color(0x55FFFFFF), CircleShape))

/** Colour rules of one app: what each picked colour turns into. New ones come from the floating colour bar or a hex code. */
@Composable
fun RecolorCard(pkg: String, sp: SharedPreferences) {
    var rules by remember(pkg) { mutableStateOf(RecolorRules.get(sp, pkg)) }
    DisposableEffect(pkg) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (k == "rc_$pkg") rules = RecolorRules.get(sp, pkg) }
        sp.registerOnSharedPreferenceChangeListener(l)
        onDispose { sp.unregisterOnSharedPreferenceChangeListener(l) }
    }
    var code by remember { mutableStateOf("") }
    var to by remember { mutableStateOf(0x000000) }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Colours", fontWeight = FontWeight.Medium)
            Text("A colour turns into another one inside this app (LSPosed module). Text on a fill that turns dark turns light.", fontSize = 13.sp, color = dim)
            rules.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Dot(r.first); Text("→", color = dim); Dot(r.second)
                    Text("#%06X  ±%d%%".format(r.first and 0xFFFFFF, r.third), fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    IconButton({ RecolorRules.put(sp, pkg, rules - r); rules = RecolorRules.get(sp, pkg) }, Modifier.size(32.dp)) { Icon(Icons.Filled.Close, "Remove", Modifier.size(18.dp)) }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Black" to 0x000000, "Charcoal" to 0x121212, "Graphite" to 0x1E1F22, "Navy" to 0x0B1220, "Warm" to 0x1A1410, "Forest" to 0x0C1510).forEach { (n, c) ->
                    FilterChip(to == c, { to = c }, { Text(n) }, leadingIcon = { Dot(c, 16) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(code, { code = it.removePrefix("#").take(6).uppercase() }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Colour code, e.g. FFFFFF") }, shape = RoundedCornerShape(14.dp))
                OutlinedButton({
                    val c = code.toIntOrNull(16)
                    if (code.length == 6 && c != null) { RecolorRules.add(sp, pkg, c or -0x1000000, to or -0x1000000, 12); code = ""; rules = RecolorRules.get(sp, pkg) }
                }, enabled = code.length == 6 && code.toIntOrNull(16) != null) { Text("Add") }
            }
            // live preview: the white page and what it becomes, updated as soon as another colour is chosen
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Dot(0xFFFFFF, 40); Text("→", fontSize = 20.sp); Dot(to, 40)
                Text("#%06X".format(to and 0xFFFFFF), fontSize = 13.sp, color = dim, fontFamily = FontFamily.Monospace)
            }
            Button({ RecolorRules.add(sp, pkg, 0xFFFFFF, to, 6); rules = RecolorRules.get(sp, pkg) }, Modifier.fillMaxWidth()) { Text("Save white → this colour") }
            Button({ sp.edit().putString("pick_go", pkg).apply() }) { Icon(Icons.Filled.Colorize, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Pick on screen") }
        }
    }
}

/** The Colours tab: the apps that have colour rules, and the floating bar for the app that is in front. */
@Composable
fun ColoursPage(apps: List<AppRow>, sp: SharedPreferences, ctx: Context) {
    var withRules by remember { mutableStateOf(RecolorRules.apps(sp)) }
    DisposableEffect(Unit) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (k != null && k.startsWith("rc_")) withRules = RecolorRules.apps(sp) }
        sp.registerOnSharedPreferenceChangeListener(l)
        onDispose { sp.unregisterOnSharedPreferenceChangeListener(l) }
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button({
            sp.edit().putString("pick_go", "*").apply()
            val home = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(home)
        }, Modifier.fillMaxWidth()) { Icon(Icons.Filled.Colorize, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Open the colour bar") }
        withRules.forEach { pkg ->
            val name = apps.firstOrNull { it.pkg == pkg }?.label ?: pkg
            Text(name, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
            RecolorCard(pkg, sp)
        }
    }
}
