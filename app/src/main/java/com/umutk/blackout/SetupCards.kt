package com.umutk.blackout

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** One card of the setup: what to do, and what to try when it does not work. */
private class SetupStep(val title: String, val text: String, val hint: String)

private val SETUP_STEPS = listOf(
    SetupStep("Turn BlackOut on in LSPosed",
        "Open LSPosed, enable BlackOut and tick this app in its scope. Then close the app from recents and open it again.",
        "Not listed? Reboot once after enabling the module. Scope changes need the app to restart."),
    SetupStep("Turn on dark pages",
        "In this app's page, switch on the dark pages option. Pages that paint themselves (Samsung Notes, readers) need it.",
        "Still light? Try the Pages section and pick the screen you see, then reopen the app."),
    SetupStep("Make the whites black",
        "Open Colors below. Tap Pick on screen, drag the ring over a white area of the app, then Save. Near-white shades match too.",
        "Nothing changed? Lower the tolerance chip (±%) to 6%, pick the white again, and reopen the app."),
    SetupStep("Check the tick marks",
        "If checkmarks or ticks look wrong, pick their colour the same way and set it to white or black.",
        "Still wrong? Pick the tick's colour from its centre, not its edge."),
    SetupStep("Check the red and green crosses",
        "Pick the red or green cross and turn it grey, then reopen the app.",
        "Only one cross changes? Pick each one; each icon has its own colour."),
)

/** The setup as large cards, one at a time: Done or Skip slides the card away; Didn't work shows a hint. Progress is kept per app. */
@Composable
fun SetupCards(app: AppRow) {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("blackout", Context.MODE_PRIVATE) }
    val key = "setup_${app.pkg}"
    var step by remember(app.pkg) { mutableStateOf(sp.getInt(key, 0)) }
    var failed by remember(app.pkg) { mutableStateOf(false) }
    fun go(n: Int) { step = n; failed = false; sp.edit().putInt(key, n).apply() }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Setup for ${app.label}", style = MaterialTheme.typography.titleSmall)
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    (slideInHorizontally(tween(320)) { it / 3 } + fadeIn(tween(320))) togetherWith
                        (slideOutHorizontally(tween(320)) { -it / 3 } + fadeOut(tween(320)))
                },
                label = "setup",
            ) { s ->
                if (s >= SETUP_STEPS.size) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("All set. Reopen the app to see the result.", style = MaterialTheme.typography.bodyLarge)
                        OutlinedButton({ go(0) }) { Text("Start over") }
                    }
                } else {
                    val st = SETUP_STEPS[s]
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Step ${s + 1} of ${SETUP_STEPS.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(st.title, style = MaterialTheme.typography.titleLarge)
                        Text(st.text, style = MaterialTheme.typography.bodyLarge)
                        if (failed) Text(st.hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ go(s + 1) }) { Text("Done") }
                            OutlinedButton({ failed = true }) { Text("Didn't work") }
                            TextButton({ go(s + 1) }) { Text("Skip") }
                        }
                    }
                }
            }
        }
    }
}
