package com.umutk.blackout

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared by the root display options (force dark, night mode, invert, black level): a read-only support check, and the value
 * from before BlackOut first changed a setting, so "Reset to original" can put it back.
 */
object RootOpts {
    /** True when the shell test [check] succeeds on this phone. Read-only. */
    fun answers(check: String): Boolean =
        Privilege.run("($check) >/dev/null 2>&1 && echo yes").text.lines().any { it.trim() == "yes" }

    /** Keeps what [read] returns, the first time only (call it before the first change). */
    fun remember(sp: SharedPreferences, key: String, read: () -> String) {
        if (!sp.contains("orig_$key")) sp.edit().putString("orig_$key", read()).apply()
    }

    fun original(sp: SharedPreferences, key: String): String? = sp.getString("orig_$key", null)
    fun forget(sp: SharedPreferences, key: String) { sp.edit().remove("orig_$key").apply() }
}

/** Under a root option: whether this phone supports it, and that it needs root. */
@Composable
fun SupportLine(mode: Privilege.Mode, supported: Boolean?) {
    val (text, color) = when {
        mode == Privilege.Mode.None -> "Needs root first (Settings > Root access)." to Color(0xFFFFB74D)
        supported == null -> "Checking this phone…" to MaterialTheme.colorScheme.onSurfaceVariant
        supported -> "Supported on this phone. Needs root." to Color(0xFF4CAF50)
        else -> "Not supported on this phone (the command or setting is missing)." to Color(0xFFFFB74D)
    }
    Text(text, fontSize = 13.sp, color = color, modifier = Modifier.padding(top = 4.dp))
}

/** Android's own whole-screen color inversion (Settings > Accessibility > Color inversion), switched through root. Off unless you switch it on. */
@Composable
fun InvertScreenCard(mode: Privilege.Mode, sp: SharedPreferences) {
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(false) }
    var supported by remember { mutableStateOf<Boolean?>(null) }
    var now by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val key = "accessibility_display_inversion_enabled"
    LaunchedEffect(mode) {
        if (mode != Privilege.Mode.None) {
            val (sup, value) = withContext(Dispatchers.IO) {
                RootOpts.answers("[ \"\$(getprop ro.build.version.sdk)\" -ge 24 ]") to Privilege.run("settings get secure $key").text.trim()
            }
            supported = sup; now = value; on = value == "1"
            sp.edit().putBoolean("inv_on", on).apply()   // the watcher keeps this value when it switches invert for an app
        }
    }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (on) "Invert the whole screen is ON" else "Invert the whole screen is off", fontWeight = FontWeight.Bold)
                    Text("Same as Settings > Accessibility > Color inversion. Photos turn negative too.", fontSize = 13.sp, color = dim)
                }
                Switch(on, { v ->
                    on = v; sp.edit().putBoolean("inv_on", v).apply()
                    scope.launch {
                        val o = withContext(Dispatchers.IO) {
                            if (v) RootOpts.remember(sp, "inv") { Privilege.run("settings get secure $key").text.trim() }
                            Privilege.run("settings put secure $key ${if (v) 1 else 0}; settings get secure $key")
                        }
                        now = o.text.trim()
                        msg = if (now == (if (v) "1" else "0")) null else "The phone did not take it: ${o.text.take(120)}"
                    }
                }, enabled = mode != Privilege.Mode.None && supported == true)
            }
            SupportLine(mode, supported)
            now?.let { Text("Android says $key = $it", fontSize = 13.sp, color = dim, modifier = Modifier.padding(top = 4.dp)) }
            OutlinedButton({
                scope.launch {
                    val (orig, o) = withContext(Dispatchers.IO) {
                        val orig = RootOpts.original(sp, "inv") ?: "0"
                        RootOpts.forget(sp, "inv")
                        orig to Privilege.run("settings put secure $key ${if (orig == "1") 1 else 0}; settings get secure $key")
                    }
                    on = orig == "1"; sp.edit().putBoolean("inv_on", on).apply()
                    now = o.text.trim(); msg = "Back to the setting from before BlackOut."
                }
            }, Modifier.padding(top = 8.dp), enabled = mode != Privilege.Mode.None) { Text("Reset to original") }
            msg?.let { Text(it, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}
