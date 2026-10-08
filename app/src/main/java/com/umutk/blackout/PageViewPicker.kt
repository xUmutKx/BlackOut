package com.umutk.blackout

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The other way for pages an app draws with its own view: list the big views of the app that is in front (root / Shizuku reads Android's view dump),
 * tick the one that is the page, and the module gives exactly that view the light/dark swap.
 */
@Composable
fun PageViewPicker(pkg: String, mode: Privilege.Mode) {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("blackout", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    var picked by remember { mutableStateOf(sp.getStringSet("pgview_$pkg", emptySet()) ?: emptySet()) }
    var found by remember { mutableStateOf(sp.getStringSet("pgcand_$pkg", emptySet())?.toList()?.sortedByDescending { it.substringAfterLast('|').toLongOrNull() ?: 0L } ?: emptyList()) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 8.dp)) {
        Text("Page view (another way)", fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Text("If the page does not change, find the view that draws it: tap Find, switch to the app and open a page. After 8 seconds the big views of the app are listed here. Tick the one that is the page.", fontSize = 13.sp, color = dim)
        OutlinedButton({
            busy = true
            scope.launch {
                android.widget.Toast.makeText(ctx, "Switch to the app now…", android.widget.Toast.LENGTH_LONG).show()
                kotlinx.coroutines.delay(8000)
                val out = withContext(Dispatchers.IO) { Privilege.run("dumpsys activity top") }
                val dm = ctx.resources.displayMetrics
                val area = dm.widthPixels.toLong() * dm.heightPixels
                val re = Regex("^\\s*([\\w.$]+)\\{[0-9a-f]+ \\S+ \\S+ (\\d+),(\\d+)-(\\d+),(\\d+)")
                var inApp = false
                val list = LinkedHashMap<String, Long>()
                out.text.lineSequence().forEach { ln ->
                    if (ln.contains("ACTIVITY ")) inApp = ln.contains(" $pkg/")
                    if (!inApp) return@forEach
                    val m = re.find(ln) ?: return@forEach
                    val cls = m.groupValues[1]
                    if (cls.startsWith("android.widget.") || cls.startsWith("android.view.") || cls.startsWith("com.android.internal.")) return@forEach
                    val a = (m.groupValues[4].toLong() - m.groupValues[2].toLong()) * (m.groupValues[5].toLong() - m.groupValues[3].toLong())
                    if (a * 5 >= area) list[cls] = maxOf(list[cls] ?: 0L, a)
                }
                found = list.map { "${it.key}|${it.value}" }.sortedByDescending { it.substringAfterLast('|').toLong() }
                sp.edit().putStringSet("pgcand_$pkg", found.toSet()).apply()
                android.widget.Toast.makeText(ctx, if (found.isEmpty()) "Nothing found. Was the app in front?" else "${found.size} views found", android.widget.Toast.LENGTH_LONG).show()
                busy = false
            }
        }, enabled = mode != Privilege.Mode.None && !busy) { Text(if (busy) "Waiting…" else "Find the page view") }
        found.forEach { e ->
            val cls = e.substringBeforeLast('|')
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(cls in picked, { v -> picked = if (v) picked + cls else picked - cls; sp.edit().putStringSet("pgview_$pkg", picked).apply() })
                Column {
                    Text(cls.substringAfterLast('.'), fontSize = 14.sp)
                    Text(cls.substringBeforeLast('.', ""), fontSize = 11.sp, color = dim, maxLines = 1)
                }
            }
        }
    }
}
