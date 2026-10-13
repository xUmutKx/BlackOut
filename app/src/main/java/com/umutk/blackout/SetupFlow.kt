package com.umutk.blackout

import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What BlackOut needs, read from the real checks each time. [recheck] reads them again (after a root command). */
class SetupState(val module: Boolean, val privilege: Boolean, val watcherNeeded: Boolean, val watcher: Boolean, val recheck: () -> Unit) {
    val moduleOrRoot get() = module || privilege
    val watcherOk get() = !watcherNeeded || watcher
    /** The setup is needed while this is false. Root or Shizuku is optional (only the overlay buttons need it). */
    val complete get() = moduleOrRoot && watcherOk
}

/** Reads the checks again every time BlackOut comes back to the front, so a step done in LSPosed or Settings shows at once. */
@Composable
fun rememberSetup(prefs: SharedPreferences, mode: Privilege.Mode): SetupState {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val tick = remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick.intValue++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    return remember(tick.intValue, mode) {
        SetupState(Status.isActive(), mode != Privilege.Mode.None, watcherNeeded(prefs), AppWatch.enabled(ctx)) { tick.intValue++ }
    }
}

/** Full-screen guided setup: one step at a time, one main button per step, then a final check. */
@Composable
fun SetupFlow(st: SetupState, mode: Privilege.Mode, prefs: SharedPreferences, onMode: (Privilege.Mode) -> Unit, onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val ok = listOf(st.moduleOrRoot, st.watcherOk, st.privilege)
    val total = 4
    var cur by rememberSaveable { mutableStateOf(ok.indexOf(false).let { if (it < 0) total - 1 else it }) }

    fun openLsposed() {
        val i = ctx.packageManager.getLaunchIntentForPackage("org.lsposed.manager")
        if (i != null) ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        else Toast.makeText(ctx, "Welcome to BlackOut: it turns the dark grey surfaces of the apps you choose pure black (AMOLED). Install LSPosed or Vector, switch BlackOut on in its scope, or use root in step 3.", Toast.LENGTH_LONG).show()
    }
    fun turnOnWatcher() {
        when {
            mode != Privilege.Mode.None -> scope.launch { withContext(Dispatchers.IO) { AppWatch.enableWithPower() }; st.recheck() }
            AppWatch.canSelfEnable(ctx) -> { AppWatch.enableSelf(ctx); st.recheck() }
            else -> ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    fun grantPower() {
        if (Privilege.shizukuRunning()) {
            Privilege.requestShizuku(); prefs.edit().putString("prefer", "shizuku").apply()
            scope.launch { delay(1500); onMode(withContext(Dispatchers.IO) { Privilege.detect("shizuku") }) }
        } else {
            prefs.edit().putString("prefer", "root").apply()
            scope.launch { onMode(withContext(Dispatchers.IO) { Privilege.detect("root") }) }
        }
    }
    val powerLabel = if (Privilege.shizukuRunning()) "Use Shizuku" else "Use root"
    val watcherLabel = if (mode != Privilege.Mode.None || AppWatch.canSelfEnable(ctx)) "Turn on" else "Open accessibility"

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Setup · Step ${cur + 1} of $total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        LinearProgressIndicator(
            { (cur + 1) / total.toFloat() },
            Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainer,
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (cur) {
                0 -> {
                    Heading("Turn BlackOut on in LSPosed")
                    Body("Open LSPosed, switch BlackOut on, and tick BlackOut and each app you want darkened in its scope. Then restart those apps. No LSPosed? Use root in step 3.")
                    StepStatus(ok[0], "The module runs in BlackOut, or root is set.", "Not done yet.")
                }
                1 -> if (!st.watcherNeeded) {
                    Heading("Accessibility is not needed")
                    Body("None of your switched-on colour features use it. You can continue.")
                } else {
                    Heading("Turn on the accessibility service")
                    Body("The colour picker and the per-app effects read where text and images are through BlackOut's accessibility service. It never stores or sends what it sees.")
                    StepStatus(ok[1], "The accessibility service is on.", "Not on yet.")
                }
                2 -> {
                    Heading("Root or Shizuku (optional)")
                    Body("The Apply buttons for overlays need root or Shizuku. Without them the LSPosed route still works.")
                    StepStatus(ok[2], "Using ${Privilege.detail}", "Not set. ${Privilege.detail}".trimEnd())
                }
                else -> {
                    Heading("Final check")
                    CheckRow("LSPosed module or root", ok[0], "Done", "Not done")
                    CheckRow("Accessibility", ok[1], if (st.watcherNeeded) "Done" else "Not needed", "Not on")
                    CheckRow("Root or Shizuku", ok[2], "Done", "Optional, not set")
                    if (!st.complete) Body("Finish is locked until the required steps are done.")
                }
            }
        }
        val last = cur == total - 1
        val stepDone = ok.getOrElse(cur) { true }
        val optional = cur == 2   // root or Shizuku: the only step that can be skipped
        // one main button per step: the action while it is not done, Next once it is
        when {
            last -> BigButton("Finish", enabled = st.complete, onClick = onFinish)
            stepDone -> BigButton("Next") { cur++ }
            cur == 0 -> BigButton("Open LSPosed", onClick = ::openLsposed)
            cur == 1 -> BigButton(watcherLabel, onClick = ::turnOnWatcher)
            else -> BigButton(powerLabel, onClick = ::grantPower)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (cur > 0) TextButton({ cur-- }, Modifier.height(48.dp)) { Text("Back", fontSize = 16.sp) } else Spacer(Modifier.height(48.dp))
            // every step can be skipped now; the Finish step still needs the required steps to be done
            if (optional) TextButton({ cur++ }, Modifier.height(48.dp)) { Text("Skip (optional)", fontSize = 16.sp, color = dim) }
            else if (!last) TextButton({ cur++ }, Modifier.height(48.dp)) { Text("Skip", fontSize = 16.sp, color = dim) }
        }
    }
}

@Composable
private fun Heading(t: String) = Text(t, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

@Composable
private fun Body(t: String) = Text(t, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun StepStatus(done: Boolean, yes: String, no: String) {
    val c = if (done) Color(0xFF4CAF50) else Color(0xFFFFB74D)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(10.dp))
        Text(if (done) yes else no, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c)
    }
}

@Composable
private fun CheckRow(label: String, done: Boolean, yes: String, no: String) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 17.sp)
            Text(if (done) yes else no, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (done) Color(0xFF4CAF50) else Color(0xFFFFB74D))
        }
    }
}

@Composable
private fun BigButton(label: String, enabled: Boolean = true, onClick: () -> Unit) =
    Button(onClick, Modifier.fillMaxWidth().height(60.dp), enabled = enabled, shape = RoundedCornerShape(18.dp)) { Text(label, fontSize = 18.sp, fontWeight = FontWeight.Medium) }
