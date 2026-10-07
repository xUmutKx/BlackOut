package com.umutk.blackout

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executors

/**
 * Accessibility service with two jobs, both without reading anything on screen (it only hears which app came to the front):
 *  1. Top layer: a dark, see-through colour layer over everything (all apps, or only the chosen ones). Needs no root.
 *  2. With root: raises the screen's black level while an app with stripped resource names is open (those cannot be themed by overlays).
 */
class AppWatch : AccessibilityService() {
    private val io = Executors.newSingleThreadExecutor()
    private var shown = ""      // what the root filter shows now: "", "crush", "pages"
    private var current = ""
    private var layer: View? = null
    private var wm: WindowManager? = null
    private lateinit var sp: SharedPreferences
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && (key.startsWith("ly_") || key.startsWith("crush") || key == "pg_on")) { applyLayer(); applyRoot() }
    }

    override fun onServiceConnected() {
        sp = getSharedPreferences("blackout", Context.MODE_PRIVATE)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        sp.registerOnSharedPreferenceChangeListener(listener)
        applyLayer()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == current || pkg == "com.android.systemui" || pkg == "android" || pkg.contains("inputmethod") || pkg.contains("keyboard")) return
        current = pkg
        applyLayer(); applyRoot()
    }

    /** The see-through top layer: on for every app, or just for the chosen ones. */
    private fun applyLayer() {
        if (!::sp.isInitialized) return
        val on = sp.getBoolean("ly_on", false) || current in (sp.getStringSet("ly_apps", emptySet()) ?: emptySet())
        if (!on) { layer?.let { try { wm?.removeView(it) } catch (_: Exception) { } }; layer = null; return }
        val alpha = (sp.getInt("ly_alpha", 45) * 255 / 100).coerceIn(0, 235)
        val color = Color.argb(alpha, Color.red(sp.getInt("ly_color", Color.BLACK)), Color.green(sp.getInt("ly_color", Color.BLACK)), Color.blue(sp.getInt("ly_color", Color.BLACK)))
        val v = layer ?: View(this).also {
            val p = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
            try { wm?.addView(it, p); layer = it } catch (_: Exception) { }
        }
        v.setBackgroundColor(color)
    }

    private fun applyRoot() {
        if (!::sp.isInitialized) return
        val crush = current in (sp.getStringSet("crush_apps", emptySet()) ?: emptySet())
        val pages = sp.getBoolean("pg_on", false)
        val want = if (crush) "crush" else if (pages) "pages" else ""
        if (want == shown) return
        io.execute {
            if (Privilege.mode == Privilege.Mode.None) Privilege.detect(sp.getString("prefer", "auto") ?: "auto")
            if (Privilege.mode == Privilege.Mode.None) return@execute
            when (want) {
                "crush" -> PageDark.applyCrush(sp.getInt("crush_level", 30))
                "pages" -> PageDark.apply(PageDark.fromPrefs(sp))
                else -> PageDark.clear()
            }
            shown = want
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (::sp.isInitialized) sp.unregisterOnSharedPreferenceChangeListener(listener)
        layer?.let { try { wm?.removeView(it) } catch (_: Exception) { } }; layer = null
        io.shutdown(); super.onDestroy()
    }

    companion object {
        const val ID = "com.umutk.blackout/com.umutk.blackout.AppWatch"

        /** Turns the watcher on through root (adds it to the enabled accessibility services). */
        fun enableWithPower(): Privilege.Out = Privilege.run(
            "cur=\$(settings get secure enabled_accessibility_services); " +
            "case \"\$cur\" in *$ID*) ;; null|\"\") settings put secure enabled_accessibility_services $ID ;; *) settings put secure enabled_accessibility_services \"\$cur:$ID\" ;; esac; " +
            "settings put secure accessibility_enabled 1; settings get secure enabled_accessibility_services"
        )

        fun enabled(c: Context): Boolean =
            (android.provider.Settings.Secure.getString(c.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains(ID)
    }
}
