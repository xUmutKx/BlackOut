package com.umutk.blackout

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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

@Composable
private fun Sw(argb: Int, size: Int = 26) = Box(Modifier.size(size.dp).clip(CircleShape).background(Color(argb)).border(1.dp, Color(0x44FFFFFF), CircleShape))

/**
 * Separate section of an app's page: every solid colour the app defines (whites and accents too), each one can be set by hand.
 * Changes are overlays like the grey ones, kept per app and made again when the app is opened.
 */
@Composable
fun AllColoursCard(app: AppRow, mode: Privilege.Mode) {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("blackout", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var all by remember(app.pkg) { mutableStateOf<List<Candidate>?>(null) }
    var busy by remember(app.pkg) { mutableStateOf(false) }
    var msg by remember(app.pkg) { mutableStateOf("") }
    var editing by remember(app.pkg) { mutableStateOf<Candidate?>(null) }
    var made by remember(app.pkg) { mutableStateOf(Overlays.overrides(sp, app.pkg)) }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Colours", fontWeight = FontWeight.Medium, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                busy = true
                scope.launch {
                    all = withContext(Dispatchers.IO) { runCatching { Arsc.scan(app.sourceDir, all = true).candidates }.getOrDefault(emptyList()) }
                    busy = false
                }
            }, enabled = !busy && mode != Privilege.Mode.None) { Text(if (all == null) "Show all colours" else "Refresh") }
            if (made.isNotEmpty()) OutlinedButton({
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { Overlays.remove(app.pkg) }
                    made.keys.forEach { Overlays.saveOverride(sp, app.pkg, it, null) }
                    made = emptyMap(); msg = r.message; busy = false
                }
            }, enabled = !busy) { Text("Restore original") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        all?.forEach { c ->
            val now = made[c.name] ?: c.color
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = mode != Privilege.Mode.None) { editing = c }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Sw(c.color); Text("→", Modifier.padding(horizontal = 6.dp)); Sw(now)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    editing?.let { c ->
        ColourEditor(app, c, sp, Overlays.overrides(sp, app.pkg)[c.name] ?: c.color,
            onDone = { col, text -> editing = null; msg = text; if (col != null) made = Overlays.overrides(sp, app.pkg) },
            onCancel = { editing = null })
    }
}

@Composable
private fun ColourEditor(app: AppRow, c: Candidate, sp: SharedPreferences, now: Int, onDone: (Int?, String) -> Unit, onCancel: () -> Unit) {
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
