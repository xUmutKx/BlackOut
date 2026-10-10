package com.umutk.blackout

import android.app.Application

/**
 * The app's entry. The LSPosed service code is kept out of this class on purpose: if that library cannot load on a phone,
 * the app still opens (the settings mirror is then simply off).
 */
class BlackOutApp : Application() {
    // kept in a field: preference listeners are held weakly by the system
    private val autoBackup = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, _ ->
        val uri = p.getString("backup_uri", null) ?: return@OnSharedPreferenceChangeListener
        try { contentResolver.openOutputStream(android.net.Uri.parse(uri), "wt")?.use { it.write(exportPrefs(p).toByteArray()) } } catch (_: Throwable) { }
    }
    override fun onCreate() {
        super.onCreate()
        try { SettingsMirror.start(this) } catch (_: Throwable) { }
        // every change goes to the backup file the user picked (it survives an uninstall; import it after a reinstall)
        getSharedPreferences("blackout", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(autoBackup)
    }
}
