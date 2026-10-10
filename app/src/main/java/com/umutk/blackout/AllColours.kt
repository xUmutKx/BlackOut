package com.umutk.blackout

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Brightness 0..1 of an ARGB color. */
private fun lumOf(c: Int): Float = (.299f * ((c shr 16) and 255) + .587f * ((c shr 8) and 255) + .114f * (c and 255)) / 255f

/** Names that say "this is a background": bg, background, backdrop. */
private fun isBg(name: String) = name.contains("bg", true) || name.contains("background", true) || name.contains("backdrop", true)

/** Greys and blue-greys (channels close together): what text usually is. */
private fun isGreyish(c: Int): Boolean {
    val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
    return maxOf(r, g, b) - minOf(r, g, b) <= 60
}

/** Names that say "this is text": text, title, label, hint. */
private fun isText(name: String) = name.contains("text", true) || name.contains("title", true) || name.contains("label", true) || name.contains("hint", true)

@Composable
private fun Sw(argb: Int, size: Int = 26) = Box(Modifier.size(size.dp).clip(CircleShape).background(Color(argb)).border(1.dp, Color(0x44FFFFFF), CircleShape))

/**
 * Separate section of an app's page: every solid color the app defines (whites and accents too), each one can be set by hand.
 * Changes are overlays like the grey ones, kept per app and made again when the app is opened.
 */
@Composable
fun AllColorsCard(app: AppRow, mode: Privilege.Mode) {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("blackout", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var all by remember(app.pkg) { mutableStateOf<List<Candidate>?>(null) }
    var busy by remember(app.pkg) { mutableStateOf(false) }
    var msg by remember(app.pkg) { mutableStateOf("") }
    var editing by remember(app.pkg) { mutableStateOf<Candidate?>(null) }
    var made by remember(app.pkg) { mutableStateOf(Overlays.overrides(sp, app.pkg)) }
    var query by remember(app.pkg) { mutableStateOf("") }
    var open by remember(app.pkg) { mutableStateOf(false) }
    var onlyDark by remember(app.pkg) { mutableStateOf(true) }
    var pickedFrom by remember(app.pkg) { mutableStateOf("FFFFFF") }   // short list by default: dark colors only
    var showAll by remember(app.pkg) { mutableStateOf(false) }   // only a few colors at first
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // collapsed by default: the list is only read when the section is opened
        Row(Modifier.fillMaxWidth().clickable { open = !open; if (open && all == null && mode != Privilege.Mode.None) { busy = true; scope.launch { all = withContext(Dispatchers.IO) { runCatching { Arsc.scan(app.sourceDir, all = true).candidates }.getOrDefault(emptyList()) }; busy = false } } }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Colors", fontWeight = FontWeight.Medium, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
        }
        Text("Tap a color in the list, then tap a color below to turn it into that. Needs BlackOut in Settings > Accessibility.", fontSize = 12.sp, color = dim)
        if (open) SwapRows(sp, app.pkg, pickedFrom, { pickedFrom = it }) { msg = it }
        if (open) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                busy = true
                scope.launch {
                    all = withContext(Dispatchers.IO) { runCatching { Arsc.scan(app.sourceDir, all = true).candidates }.getOrDefault(emptyList()) }
                    busy = false
                }
            }, enabled = !busy && mode != Privilege.Mode.None) { Text(if (all == null) "Show all colors" else "Refresh") }
            if (made.isNotEmpty()) OutlinedButton({
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { Overlays.remove(app.pkg) }
                    made.keys.forEach { Overlays.saveOverride(sp, app.pkg, it, null) }
                    made = emptyMap(); msg = r.message; busy = false
                }
            }, enabled = !busy) { Text("Restore original") }
        }
        if (open && busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (open) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Only dark colors", Modifier.weight(1f), fontSize = 13.sp)
            Switch(onlyDark, { onlyDark = it })
        }
        // one tap for the usual cases: every light background made black, every dark text made white
        // R8 hides some resource names ("..._obfuscated"): those colors cannot be targeted by name, so they go in by value as a rule
        fun quick(cands: List<Candidate>, col: Int, label: String) {
            if (cands.isEmpty()) { msg = "Nothing to change"; return }
            busy = true
            scope.launch {
                val (hidden, named) = cands.partition { it.name.contains("obfuscated", true) }
                hidden.forEach { RecolorRules.add(sp, app.pkg, it.color, col, 3) }
                var ok = true; var text = ""
                if (named.isNotEmpty()) {
                    val r = withContext(Dispatchers.IO) { Overlays.apply(app.pkg, named.map { it.name to col }) }
                    ok = r.ok; text = r.message
                    if (r.ok) named.forEach { Overlays.saveOverride(sp, app.pkg, it.name, col) }
                }
                made = Overlays.overrides(sp, app.pkg)
                msg = if (ok) "$label (${cands.size}). Reopen the app." else text
                busy = false
            }
        }
        if (open) all?.let { list ->
            OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Search colors") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ quick(list.filter { isBg(it.name) && lumOf(it.color) > .8f }, 0xFF000000.toInt(), "Backgrounds made black") },
                    enabled = !busy && mode != Privilege.Mode.None) { Text("Backgrounds → black") }
                OutlinedButton({ quick(list.filter { (isText(it.name) || it.name.contains("obfuscated", true)) && lumOf(it.color) in .25f..0.6f && isGreyish(it.color) }, 0xFFFFFFFF.toInt(), "Text made white") },
                    enabled = !busy && mode != Privilege.Mode.None) { Text("Text → white") }
            }
            if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp, color = dim)
        }
        if (open) all?.sortedByDescending { isBg(it.name) }?.filter { (query.isBlank() || it.name.contains(query, true)) && (!onlyDark || lumOf(it.color) < .5f) }?.let { list -> list.take(if (showAll) list.size else 8) to list.size }?.let { (rows, total) ->
          rows.forEach { c ->
            val now = made[c.name] ?: c.color
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = mode != Privilege.Mode.None) { pickedFrom = "%06X".format(c.color and 0xFFFFFF) }.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Sw(c.color); Text("→", Modifier.padding(horizontal = 6.dp)); Sw(now)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
          }
            if (total > 8) OutlinedButton({ showAll = !showAll }) { Text(if (showAll) "Show fewer" else "Show all colors ($total)", fontSize = 13.sp) }
        }
    }

    editing?.let { c ->
        ColorEditor(app, c, sp, Overlays.overrides(sp, app.pkg)[c.name] ?: c.color,
            onDone = { col, text -> editing = null; msg = text; if (col != null) made = Overlays.overrides(sp, app.pkg) },
            onCancel = { editing = null })
    }
}

@Composable
private fun ColorEditor(app: AppRow, c: Candidate, sp: SharedPreferences, now: Int, onDone: (Int?, String) -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var hex by remember(c.name) { mutableStateOf("%06X".format(now and 0xFFFFFF)) }
    var busy by remember(c.name) { mutableStateOf(false) }
    val presets = listOf(0xFF000000.toInt(), 0xFF121212.toInt(), 0xFF1E1E1E.toInt(), 0xFF0B1020.toInt(), 0xFFFFFFFF.toInt(), 0xFF8C9EFF.toInt())
    AlertDialog(onDismissRequest = onCancel,
        title = { Text(c.name, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { col -> Box(Modifier.size(34.dp).clip(CircleShape).background(Color(col)).border(1.dp, Color(0x55FFFFFF), CircleShape).clickable { hex = "%06X".format(col and 0xFFFFFF) }) }
                }
                OutlinedTextField(hex, { hex = it.uppercase().filter { ch -> ch in '0'..'9' || ch in 'A'..'F' }.take(6) }, singleLine = true, label = { Text("#") }, enabled = !busy)
            }
        },
        confirmButton = {
            TextButton({
                val v = hex.toIntOrNull(16) ?: return@TextButton
                if (hex.length != 6) return@TextButton
                val col = v or -0x1000000
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { Overlays.apply(app.pkg, listOf(c.name to col)) }
                    if (r.ok) Overlays.saveOverride(sp, app.pkg, c.name, col)
                    busy = false
                    android.widget.Toast.makeText(ctx, if (r.ok) "Done. Reopen the app." else r.message, android.widget.Toast.LENGTH_LONG).show()
                    onDone(if (r.ok) col else null, r.message)
                }
            }, enabled = !busy && hex.length == 6) { Text("Apply") }
        },
        dismissButton = { TextButton(onCancel) { Text("Cancel") } })
}

/** Any color can be turned into another: type the source and the new color (hex). Starts as pure white to black. */
@Composable
private fun SwapRows(sp: SharedPreferences, pkg: String, from: String, onFrom: (String) -> Unit, onMsg: (String) -> Unit) {
    var to by remember(pkg) { mutableStateOf("000000") }
    fun hex(v: String) = (v.toIntOrNull(16) ?: 0) or -0x1000000
    fun clean(v: String) = v.uppercase().filter { ch -> ch in '0'..'9' || ch in 'A'..'F' }.take(6)
    // a live preview: the color it is now, and what it becomes
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(from, { onFrom(clean(it)) }, singleLine = true, label = { Text("from #") }, modifier = Modifier.weight(1f))
        Sw(if (from.length == 6) hex(from) else 0xFF000000.toInt(), 34)
        Text("→")
        Sw(if (to.length == 6) hex(to) else 0xFF000000.toInt(), 34)
        OutlinedTextField(to, { to = clean(it) }, singleLine = true, label = { Text("to #") }, modifier = Modifier.weight(1f))
    }
    // quick picks for the "to" color
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0xFF000000.toInt(), 0xFF121212.toInt(), 0xFF0B1020.toInt(), 0xFFFFFFFF.toInt(), 0xFFF8FAFC.toInt()).forEach { col ->
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(col)).border(1.dp, Color(0x55FFFFFF), CircleShape).clickable {
                // a tap on a color turns the picked source into it, at once
                if (from.length == 6) { RecolorRules.add(sp, pkg, hex(from), col, 0); onMsg("#$from → #%06X. Reopen the app.".format(col and 0xFFFFFF)) } else onMsg("Pick a color from the list first.")
            })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({
            RecolorRules.add(sp, pkg, hex(from), hex(to), 0)
            onMsg("Set #$from → #$to. Reopen the app.")
        }, enabled = from.length == 6 && to.length == 6) { Text("Apply") }
        OutlinedButton({
            val key = hex(from) and 0xFFFFFF
            RecolorRules.put(sp, pkg, RecolorRules.get(sp, pkg).filter { (it.first and 0xFFFFFF) != key })
            onMsg("Removed #$from. Reopen the app.")
        }) { Text("Remove") }
    }
}
