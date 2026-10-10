package com.umutk.blackout

import android.app.Application
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/** Copies BlackOut's own settings into the LSPosed remote preferences, so the modern hooks in other apps read the same values. */
object SettingsMirror : XposedServiceHelper.OnServiceListener {
    private lateinit var local: SharedPreferences
    private var remote: SharedPreferences? = null
    private val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> mirror(key) }

    fun start(app: Application) {
        local = app.getSharedPreferences("blackout", Application.MODE_PRIVATE)
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        remote = service.getRemotePreferences("blackout")
        local.registerOnSharedPreferenceChangeListener(changed)
        local.all.keys.forEach { mirror(it) }
    }

    override fun onServiceDied(service: XposedService) { remote = null }

    private fun mirror(key: String?) {
        if (key == null) return
        val r = remote ?: return
        val e = r.edit()
        when (val v = local.all[key]) {
            null -> e.remove(key)
            is Boolean -> e.putBoolean(key, v)
            is Float -> e.putFloat(key, v)
            is Int -> e.putInt(key, v)
            is Long -> e.putLong(key, v)
            is String -> e.putString(key, v)
            is Set<*> -> e.putStringSet(key, v.filterIsInstance<String>().toSet())
        }
        e.apply()
    }
}
