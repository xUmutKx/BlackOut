package com.umutk.blackout

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipFile

/** How an app draws its screen decides which BlackOut method can reach it. */
class AppKind(val title: String, val advice: String)

object AppKindScan {
    /** Looks inside the APK (and its splits, where the native libraries live) for the drawing engine the app brings with it. */
    fun detect(ctx: Context, pkg: String): AppKind? = try {
        val info = ctx.packageManager.getApplicationInfo(pkg, 0)
        val files = listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())
        val names = HashSet<String>()
        for (f in files) try { ZipFile(f).use { z -> for (e in z.entries()) names.add(e.name) } } catch (_: Throwable) { }
        fun has(s: String) = names.any { it.contains(s) }
        when {
            has("libflutter.so") || has("flutter_assets") -> AppKind("Flutter app",
                "It paints every pixel itself. Color rules and overlays cannot change it, whatever the color. Use Dark pages (the screen filter) or Invert for it.")
            has("libunity.so") || has("libil2cpp.so") -> AppKind("Unity app",
                "A game engine draws the screen. Color rules and overlays cannot change it. Use Dark pages (the screen filter) or Invert.")
            has("libgodot") || has("libcocos") -> AppKind("Game engine app",
                "The screen is drawn by the engine, not by Android views. Use Dark pages (the screen filter) or Invert.")
            has("assets/www/") || has("cordova") || has("capacitor") -> AppKind("Web app (WebView)",
                "The pages are web content. Color rules cannot reach web colors. Use Dark pages, Force dark, or the web black option.")
            has("androidx.compose") || has("compose.runtime") -> AppKind("Jetpack Compose app",
                "Colors are set in the code (Material theme), so overlays often change nothing. Auto (screen filter) reaches its greys; color rules need the LSPosed module.")
            // React Native draws its screen from a JavaScript bundle: colors are set in code, so resource overlays cannot reach them
            has("index.android.bundle") || has("libreactnativejni") || has("libhermes") -> AppKind("React Native app",
                "The screen is drawn from JavaScript, so color rules and overlays cannot reach it. Use Auto (screen filter) or Dark pages.")
            else -> AppKind("Native Android app", "Color rules, overlays and Dark pages can all reach it.")
        }
    } catch (_: Throwable) { null }
}

/** A small card at the top of an app's page: how it draws, and the one screen-filter switch for it. Invert lives in Methods, off by default. */
@Composable
fun AppKindCard(pkg: String) {
    val ctx = LocalContext.current
    var kind by remember(pkg) { mutableStateOf<AppKind?>(null) }
    LaunchedEffect(pkg) { kind = withContext(Dispatchers.IO) { AppKindScan.detect(ctx, pkg) } }
    val sp = remember { ctx.getSharedPreferences("blackout", Context.MODE_PRIVATE) }
    var pages by remember(pkg) { mutableStateOf(sp.getStringSet("pages_apps", emptySet()) ?: emptySet()) }
    kind?.let { k ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.Info, null, Modifier.size(20.dp))
                    Text(k.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                }
                Text(k.advice, style = MaterialTheme.typography.bodySmall)
                // what each method actually did in this app, from the module's last report and the saved rules
                val report = remember(pkg) { HookReport.read(ctx, pkg) }
                val rules = remember(pkg) { RecolorRules.get(sp, pkg).size }
                val overrides = remember(pkg) { Overlays.overrides(sp, pkg).size }
                val fresh = report != null && System.currentTimeMillis() - report.at < 120_000L
                Text("Discovery", style = MaterialTheme.typography.labelLarge)
                DiscoveryRow(Icons.Filled.Extension, "LSPosed module inside this app",
                    if (report == null) "no report: not active here (check LSPosed scope)" else if (fresh) "active, reporting now" else "active earlier, not running now")
                DiscoveryRow(Icons.Filled.Palette, "Color rules", if (rules == 0) "none saved" else "$rules saved")
                DiscoveryRow(Icons.Filled.Layers, "Overlays (root)", if (overrides == 0) "none saved" else "$overrides saved")
                if (report != null) DiscoveryRow(Icons.Filled.DarkMode, "Force dark / changed by the module",
                    "force dark: ${if (report.fd) "on" else "off"} · forced ${report.forced} · greys ${report.greys} · texts ${report.texts} · fills ${report.fills}")
            }
        }
    }
}

@Composable
private fun DiscoveryRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.labelSmall)
        }
    }
}
