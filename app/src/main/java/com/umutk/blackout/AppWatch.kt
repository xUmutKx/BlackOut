package com.umutk.blackout

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** The dark layer: one flat colour with holes cut where text and images are, so those keep their full brightness. */
private class LayerView(c: Context) : View(c) {
    var dim = 0
    var holes: List<Rect> = emptyList()
    private val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    init { setLayerType(LAYER_TYPE_HARDWARE, null) }
    override fun onDraw(c: Canvas) {
        c.drawColor(dim)
        if (holes.isEmpty()) return
        val loc = IntArray(2); getLocationOnScreen(loc)
        val pad = 2f * resources.displayMetrics.density
        for (r in holes) c.drawRect(r.left - loc[0] - pad, r.top - loc[1] - pad, r.right - loc[0] + pad, r.bottom - loc[1] + pad, clear)
    }
}

/**
 * Accessibility service with two jobs:
 *  1. Top layer: a dark, see-through colour layer over everything (all apps, or only the chosen ones), with holes over text and images. Needs no root.
 *     It only looks at where text and images are (bounds and class names), never at what they say; nothing is stored or sent.
 *  2. With root: raises the screen's black level, or applies Dark pages, while the chosen apps are open.
 */
class AppWatch : AccessibilityService() {
    private val io = Executors.newSingleThreadExecutor()
    private var shown = ""      // what the root filter shows now: "", "crush", "pages"
    private var current = ""
    private var layer: LayerView? = null
    private val ui = Handler(Looper.getMainLooper())
    private var holesPending = false
    private var wm: WindowManager? = null
    private lateinit var sp: SharedPreferences
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && (key.startsWith("ly_") || key.startsWith("crush") || key.startsWith("pg_") || key == "pages_apps")) { if (key.startsWith("pg_") || key.startsWith("crush")) shown = ""; applyLayer(); applyRoot() }
    }

    override fun onServiceConnected() {
        sp = getSharedPreferences("blackout", Context.MODE_PRIVATE)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        sp.registerOnSharedPreferenceChangeListener(listener)
        applyLayer()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || e.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) { if (layer != null) scheduleHoles(); return }
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString().orEmpty()
        // the system UI (shade, volume) is ignored, but the recents screen counts as leaving the app
        if (pkg == "com.android.systemui" && !cls.contains("Recents", true)) return
        if (pkg == current || pkg == "android" || pkg.contains("inputmethod") || pkg.contains("keyboard")) return
        current = pkg
        applyLayer(); applyRoot()
        if (layer != null) scheduleHoles()
    }

    /** The see-through top layer: on for every app, or just for the chosen ones. */
    private fun applyLayer() {
        if (!::sp.isInitialized) return
        val on = sp.getBoolean("ly_on", false) || current in (sp.getStringSet("ly_apps", emptySet()) ?: emptySet())
        if (!on) { layer?.let { try { wm?.removeView(it) } catch (_: Exception) { } }; layer = null; return }
        val alpha = (sp.getInt("ly_alpha", 45) * 255 / 100).coerceIn(0, 235)
        val color = Color.argb(alpha, Color.red(sp.getInt("ly_color", Color.BLACK)), Color.green(sp.getInt("ly_color", Color.BLACK)), Color.blue(sp.getInt("ly_color", Color.BLACK)))
        val v = layer ?: LayerView(this).also {
            val p = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
            try { wm?.addView(it, p); layer = it } catch (_: Exception) { }
        }
        v.dim = color
        v.invalidate()
        scheduleHoles()
    }

    private fun scheduleHoles() {
        if (holesPending) return
        holesPending = true
        ui.postDelayed({ holesPending = false; updateHoles() }, 350)
    }

    /** Finds where text and images are in the window in front and cuts those out of the layer. */
    private fun updateHoles() {
        val lv = layer ?: return
        if (!sp.getBoolean("ly_protect", true)) { if (lv.holes.isNotEmpty()) { lv.holes = emptyList(); lv.invalidate() }; return }
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        val dm = resources.displayMetrics
        val minPx = 12 * dm.density
        val maxArea = 0.8f * dm.widthPixels * dm.heightPixels
        val out = ArrayList<Rect>()
        var count = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || count > 900 || depth > 40) return
            count++
            if (n.isVisibleToUser) {
                val cn = n.className?.toString().orEmpty()
                val isText = !n.text.isNullOrBlank()
                val isImage = cn.contains("Image", true)
                if (isText || isImage) {
                    val r = Rect(); n.getBoundsInScreen(r)
                    if (r.width() >= minPx && r.height() >= minPx * 0.6f && r.width().toFloat() * r.height() < maxArea) out.add(r)
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        lv.holes = out
        lv.invalidate()
    }

    private fun applyRoot() {
        if (!::sp.isInitialized) return
        val crush = current in (sp.getStringSet("crush_apps", emptySet()) ?: emptySet())
        val pages = sp.getBoolean("pg_on", false) || current in (sp.getStringSet("pages_apps", emptySet()) ?: emptySet())
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
        ui.removeCallbacksAndMessages(null)
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
