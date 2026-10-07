package com.umutk.blackout

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AccentC = Color(0xFF8C9EFF)

/** Settings > About: a glowing moon with twinkling stars, who made it, version and device, the GitHub link and the privacy promise. */
@Composable
fun AboutPage() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val ver = remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" } }
    val moon = remember { PathParser().parsePathString("M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9s9,-4.03 9,-9c0,-0.46 -0.04,-0.92 -0.1,-1.36c-0.98,1.37 -2.58,2.26 -4.4,2.26c-2.98,0 -5.4,-2.42 -5.4,-5.4c0,-1.81 0.89,-3.42 2.26,-4.4C12.92,3.04 12.46,3 12,3z").toPath() }
    val t = rememberInfiniteTransition(label = "about")
    val glow by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
    val tw1 by t.animateFloat(0.2f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "s1")
    val tw2 by t.animateFloat(1f, 0.2f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "s2")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(Modifier.size(180.dp)) {
            drawCircle(AccentC.copy(alpha = .22f * glow), size.minDimension * .5f)
            drawCircle(AccentC.copy(alpha = .12f * glow), size.minDimension * .36f)
            scale(size.width / 24f, pivot = Offset.Zero) {
                drawPath(moon, AccentC, style = Stroke(1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawCircle(AccentC.copy(alpha = tw1), 0.65f, Offset(18.5f, 5f))
                drawCircle(AccentC.copy(alpha = tw2), 0.45f, Offset(21f, 8.5f))
                drawCircle(AccentC.copy(alpha = tw1), 0.35f, Offset(4.5f, 4f))
            }
        }
        Text("BlackOut", fontSize = 30.sp, fontWeight = FontWeight.Black, color = AccentC)
        Text("by UmutK", fontSize = 16.sp, color = AccentC, fontWeight = FontWeight.Medium)
        Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Version" to ver, "Package" to ctx.packageName, "Android" to "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
                    "Device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}").forEach { (k, v) ->
                    Row { Text(k, Modifier.weight(1f), color = cs.onSurfaceVariant, fontSize = 14.sp); Text(v, fontSize = 14.sp) }
                }
            }
        }
        OutlinedButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/xUmutKx/BlackOut")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, Modifier.fillMaxWidth()) { Text("GitHub · xUmutKx/BlackOut") }
        Text("Turns the grey surfaces of your apps into pure AMOLED black: with root, with an accessibility layer, or with an LSPosed module.", fontSize = 14.sp, color = cs.onSurfaceVariant)
        PrivacyNote()
    }
}
