package com.umutk.blackout

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** For apps with stripped resource names: deepen the dark greys of the whole screen, but only while this app is in front. */
@Composable
fun CrushCard(pkg: String, mode: Privilege.Mode) {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("blackout", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf(sp.getStringSet("crush_apps", emptySet()) ?: emptySet()) }
    var level by remember { mutableFloatStateOf(sp.getInt("crush_level", 30).toFloat()) }
    var watching by remember { mutableStateOf(AppWatch.enabled(ctx)) }
    var msg by remember { mutableStateOf<String?>(null) }
    val on = pkg in apps
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("This app hides its colours", fontWeight = FontWeight.Bold)
            Text("Overlays cannot recolour it. With root, BlackOut can darken the screen while this app is open.", fontSize = 13.sp, color = dim)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Darken while this app is open", Modifier.weight(1f), fontSize = 14.sp)
                Switch(on, { v -> apps = if (v) apps + pkg else apps - pkg; sp.edit().putStringSet("crush_apps", apps).apply() })
            }
            Text("Greys up to ${level.toInt()} / 255 become pure black", fontSize = 13.sp)
            Slider(level, { level = it }, valueRange = 8f..80f, onValueChangeFinished = { sp.edit().putInt("crush_level", level.toInt()).apply() })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ scope.launch { withContext(Dispatchers.IO) { PageDark.applyCrush(level.toInt()) } } }, enabled = mode != Privilege.Mode.None) { Text("Try now") }
                OutlinedButton({ scope.launch { withContext(Dispatchers.IO) { PageDark.clear() } } }, enabled = mode != Privilege.Mode.None) { Text("Undo") }
            }
            Text(if (watching) "Watcher is on. It switches by itself." else "Watcher is off. Turn it on so this only applies to this app.",
                fontSize = 13.sp, color = if (watching) Color(0xFF4CAF50) else Color(0xFFFFB74D), modifier = Modifier.padding(top = 6.dp))
            if (!watching) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    scope.launch {
                        val o = withContext(Dispatchers.IO) { AppWatch.enableWithPower() }
                        watching = AppWatch.enabled(ctx); msg = if (watching) null else "Could not switch it on: ${o.text.take(120)}"
                    }
                }, enabled = mode != Privilege.Mode.None) { Text("Turn on (root)") }
                OutlinedButton({ ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open accessibility") }
            }
            msg?.let { Text(it, fontSize = 13.sp, color = Color(0xFFF44336)) }
        }
    }
}
