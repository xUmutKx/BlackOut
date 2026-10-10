package com.umutk.blackout

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/** All BlackOut settings and color rules as one JSON text. */
fun exportPrefs(sp: SharedPreferences): String {
    val o = JSONObject()
    for ((k, v) in sp.all) when (v) {
        is Set<*> -> o.put(k, JSONArray(v.map { it.toString() }))
        else -> o.put(k, v)
    }
    return o.toString(2)
}

/** Replaces the current settings with the ones in [text]. */
fun importPrefs(sp: SharedPreferences, text: String) {
    val o = JSONObject(text)
    val e = sp.edit().clear()
    for (k in o.keys()) when (val v = o.get(k)) {
        is Boolean -> e.putBoolean(k, v)
        is Int -> e.putInt(k, v)
        is Double -> e.putFloat(k, v.toFloat())
        is String -> e.putString(k, v)
        is JSONArray -> e.putStringSet(k, (0 until v.length()).map { v.getString(it) }.toSet())
    }
    e.apply()
}

@Composable
fun DataCard(sp: SharedPreferences) {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf("") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) msg = runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(exportPrefs(sp).toByteArray()) }; "Saved." }.getOrElse { "Save failed: ${it.message}" }
    }
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) msg = runCatching { importPrefs(sp, ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }); "Loaded. Reopen the app." }.getOrElse { "Load failed: ${it.message}" }
    }
    val auto = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            sp.edit().putString("backup_uri", uri.toString()).apply()
            msg = runCatching { ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(exportPrefs(sp).toByteArray()) }; "Auto-backup on: every change is saved to that file." }.getOrElse { "Auto-backup failed: ${it.message}" }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Save or load every BlackOut setting and color rule as one JSON file.", fontSize = 14.sp)
        Button({ save.launch("blackout-settings.json") }) { Text("Export settings") }
        OutlinedButton({ load.launch(arrayOf("application/json", "text/*")) }) { Text("Import settings") }
        OutlinedButton({ auto.launch("blackout-autobackup.json") }) { Text("Auto-backup file") }
        if (msg.isNotEmpty()) Text(msg, fontSize = 13.sp)
    }
}
