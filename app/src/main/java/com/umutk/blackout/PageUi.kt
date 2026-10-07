package com.umutk.blackout

import android.content.SharedPreferences
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PagePresets = listOf("AMOLED" to 0x000000, "Charcoal" to 0x121212, "Graphite" to 0x1E1F22, "Navy" to 0x0B1220, "Warm" to 0x1A1410, "Forest" to 0x0C1510)

/** Turns white pages into a colour of your choice (live preview), text white, photos hue-corrected. Runs through root / Shizuku. */
@Composable
fun PageCard(mode: Privilege.Mode, prefs: SharedPreferences) {
    val scope = rememberCoroutineScope()
    var r by remember { mutableFloatStateOf(prefs.getFloat("pg_r", 0f)) }
    var g by remember { mutableFloatStateOf(prefs.getFloat("pg_g", 0f)) }
    var b by remember { mutableFloatStateOf(prefs.getFloat("pg_b", 0f)) }
    var txt by remember { mutableFloatStateOf(prefs.getFloat("pg_text", 235f)) }
    var hueSafe by remember { mutableStateOf(prefs.getBoolean("pg_hue", true)) }
    var on by remember { mutableStateOf(prefs.getBoolean("pg_on", false)) }
    var msg by remember { mutableStateOf<String?>(null) }
    val bg = (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    val t = txt.toInt().let { (it shl 16) or (it shl 8) or it }
    val cfg = PageDark.Cfg(bg, t, hueSafe)
    fun save() { prefs.edit().putFloat("pg_r", r).putFloat("pg_g", g).putFloat("pg_b", b).putFloat("pg_text", txt).putBoolean("pg_hue", hueSafe).apply() }
    fun push(h: Boolean = hueSafe) {
        save()
        if (on) scope.launch {
            val o = withContext(Dispatchers.IO) { PageDark.apply(PageDark.Cfg(bg, t, h)) }
            msg = if (o.code == 0) null else "Failed: ${o.text}"
        }
    }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(16.dp)) {
                Text("Preview", fontWeight = FontWeight.Bold)
                Text("Left: the page as it is. Right: how it will look.", fontSize = 13.sp, color = dim)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MockPage(null, Modifier.weight(1f))
                    MockPage(ColorFilter.colorMatrix(ColorMatrix(PageDark.colorMatrix(cfg))), Modifier.weight(1f))
                }
            }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Page colour", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Box(Modifier.size(26.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or bg)).border(1.dp, Color(0x66FFFFFF), CircleShape))
                    Text("  #%06X".format(bg), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
                Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PagePresets.forEach { (n, c) ->
                        AssistChip({ r = ((c shr 16) and 255).toFloat(); g = ((c shr 8) and 255).toFloat(); b = (c and 255).toFloat(); push() }, { Text(n) },
                            leadingIcon = { Box(Modifier.size(16.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or c)).border(1.dp, Color(0x66FFFFFF), CircleShape)) })
                    }
                }
                ColourSlider("Red", r, Color(0xFFEF5350), { r = it }) { push() }
                ColourSlider("Green", g, Color(0xFF66BB6A), { g = it }) { push() }
                ColourSlider("Blue", b, Color(0xFF42A5F5), { b = it }) { push() }
                Text("Text brightness ${(txt / 255f * 100).toInt()}%", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                Slider(txt, { txt = it }, valueRange = 140f..255f, onValueChangeFinished = { push() })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep photo colours", fontSize = 14.sp)
                        Text("Rotates the hue back so photos do not look like negatives.", fontSize = 13.sp, color = dim)
                    }
                    Switch(hueSafe, { hueSafe = it; push(it) })
                }
            }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (on) "Dark pages are ON" else "Dark pages are off", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Switch(on, { v ->
                        on = v; prefs.edit().putBoolean("pg_on", v).apply(); save()
                        scope.launch {
                            val o = withContext(Dispatchers.IO) { if (v) PageDark.apply(cfg) else PageDark.clear() }
                            msg = if (o.code == 0) null else "Failed: ${o.text}"
                        }
                    }, enabled = mode != Privilege.Mode.None)
                }
                if (mode == Privilege.Mode.None) Text("Needs root or Shizuku first (Settings > Access).", color = Color(0xFFFFB74D), fontSize = 13.sp)
                msg?.let { Text(it, fontSize = 13.sp, color = Color(0xFFF44336), modifier = Modifier.padding(top = 4.dp)) }
                Text("The effect covers the whole screen and lasts until the phone restarts (turn it on again then). Only the white becomes your colour and text turns white; photos are kept as close to normal as a screen-wide filter can.", fontSize = 13.sp, color = dim, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun ColourSlider(name: String, v: Float, tint: Color, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.width(52.dp), fontSize = 13.sp)
        Slider(v, onChange, Modifier.weight(1f), valueRange = 0f..80f, onValueChangeFinished = onDone, colors = SliderDefaults.colors(thumbColor = tint, activeTrackColor = tint))
        Text(v.toInt().toString(), Modifier.width(28.dp), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

/** A tiny fake web page (title, text lines, a photo, a button) drawn in normal colours, optionally pushed through a colour filter. */
@Composable
fun MockPage(cf: ColorFilter?, modifier: Modifier) {
    Canvas(modifier.height(170.dp).clip(RoundedCornerShape(10.dp))) {
        val w = size.width; val h = size.height
        fun rect(c: Color, x: Float, y: Float, rw: Float, rh: Float) = drawRect(c, Offset(x, y), Size(rw, rh), colorFilter = cf)
        rect(Color.White, 0f, 0f, w, h)
        rect(Color(0xFF111111), w * .08f, h * .07f, w * .55f, h * .07f)
        for (i in 0..3) rect(Color(0xFF222222), w * .08f, h * (.20f + i * .06f), w * (.84f - (i % 2) * .2f), h * .03f)
        rect(Color(0xFF4FA3E3), w * .08f, h * .46f, w * .84f, h * .30f)
        drawCircle(Color(0xFFFFC107), w * .06f, Offset(w * .72f, h * .54f), colorFilter = cf)
        rect(Color(0xFF3E9B4F), w * .08f, h * .64f, w * .84f, h * .12f)
        rect(Color(0xFF1A73E8), w * .08f, h * .83f, w * .34f, h * .09f)
    }
}
