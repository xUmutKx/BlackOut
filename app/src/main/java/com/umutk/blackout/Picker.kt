package com.umutk.blackout

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Rules are kept as "RRGGBB>RRGGBB>percent;..." under rc_<package>. */
object RecolorRules {
    fun get(sp: SharedPreferences, pkg: String): List<Triple<Int, Int, Int>> = sp.getString("rc_$pkg", "").orEmpty().split(';').mapNotNull { p ->
        val a = p.split('>')
        if (a.size < 3) null else try { Triple(a[0].toLong(16).toInt() or -0x1000000, a[1].toLong(16).toInt() or -0x1000000, a[2].toInt()) } catch (_: Throwable) { null }
    }

    fun put(sp: SharedPreferences, pkg: String, rules: List<Triple<Int, Int, Int>>) {
        val s = rules.joinToString(";") { "%06X>%06X>%d".format(it.first and 0xFFFFFF, it.second and 0xFFFFFF, it.third) }
        sp.edit().putString("rc_$pkg", s).apply()
    }

    fun add(sp: SharedPreferences, pkg: String, from: Int, to: Int, tol: Int) =
        put(sp, pkg, get(sp, pkg).filter { (it.first and 0xFFFFFF) != (from and 0xFFFFFF) } + Triple(from, to, tol))

    fun apps(sp: SharedPreferences): List<String> = sp.all.keys.filter { it.startsWith("rc_") && !sp.getString(it, "").isNullOrBlank() }.map { it.removePrefix("rc_") }
}

/**
 * The floating color bar: a ring to drag over any color on screen, the color under it, what it should turn into, and a tick to keep the rule for [target].
 * The screen is read with the accessibility screenshot (Android 11+), so no root is needed; the ring is hidden for that moment.
 */
private fun lumOf(c: Int) = (.299f * ((c shr 16) and 255) + .587f * ((c shr 8) and 255) + .114f * (c and 255)) / 255f

class ColorPicker(private val svc: AccessibilityService, private val wm: WindowManager, private val sp: SharedPreferences, private val ui: Handler, private val foreground: () -> String = { "" }) {
    private var bar: View? = null
    private var ring: RingView? = null
    private var ringLp: WindowManager.LayoutParams? = null
    private var target = ""
    private var picked = 0
    private var to = 0xFF000000.toInt()
    private var tol = 12
    private var swatch: View? = null
    private var hex: TextView? = null
    private var tolText: TextView? = null
    private var promptBox: View? = null     // "make this color X?" after a dropper pick: Yes saves it at once
    private var appRow: LinearLayout? = null  // the app's own colours, read from its resources: tap one, no screen reading needed
    private var promptText: TextView? = null
    private val dp = svc.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    private class RingView(c: android.content.Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        override fun onDraw(c: Canvas) {
            val m = width / 2f
            p.color = Color.BLACK; p.strokeWidth = 5f * resources.displayMetrics.density; c.drawCircle(m, m, m - 6f * resources.displayMetrics.density, p)
            p.color = Color.WHITE; p.strokeWidth = 2.5f * resources.displayMetrics.density; c.drawCircle(m, m, m - 6f * resources.displayMetrics.density, p)
            p.style = Paint.Style.FILL; p.color = Color.WHITE; c.drawCircle(m, m, 3f * resources.displayMetrics.density, p); p.style = Paint.Style.STROKE
        }
    }

    private fun dot(color: Int, size: Int = 30) = View(svc).apply {
        layoutParams = LinearLayout.LayoutParams(px(size), px(size)).apply { marginStart = px(5); marginEnd = px(5) }
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke(px(1), 0x66FFFFFF) }
    }

    fun show(pkg: String) {
        hide()
        target = pkg
        // two short rows so the bar fits a phone: top = what was picked and close, bottom = the target and save
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(10), px(6), px(10), px(8))
            background = GradientDrawable().apply { cornerRadius = px(22).toFloat(); setColor(0xF0141418.toInt()); setStroke(px(1), 0x33FFFFFF) }
        }
        val top = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val sw = dot(0xFF777777.toInt(), 30).also { swatch = it }; top.addView(sw)
        val hx = TextView(svc).apply { text = ""; setTextColor(Color.WHITE); textSize = 13f; typeface = android.graphics.Typeface.MONOSPACE; setPadding(px(4), 0, px(8), 0) }.also { hex = it }
        top.addView(hx, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(svc).apply { text = "✕"; setTextColor(0xFFFFFFFF.toInt()); textSize = 18f; setPadding(px(12), px(2), px(6), px(2)); setOnClickListener { hide() } })
        box.addView(top)
        val bottom = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(TextView(svc).apply { text = "→"; setTextColor(0xFF9A9AA6.toInt()); textSize = 16f; setPadding(0, 0, px(4), 0) })
        val targets = listOf(0xFF000000.toInt(), 0xFF121212.toInt(), 0xFF0B1020.toInt(), 0xFFFFFFFF.toInt())
        val dots = targets.map { c -> dot(c, 26).apply { setOnClickListener { to = c; dotsShown(bottom, this) } } }
        dots.forEach { bottom.addView(it) }
        dotsShown(bottom, dots[0])
        val tv = TextView(svc).apply { text = "±$tol%"; setTextColor(0xFFC5CAE9.toInt()); textSize = 12f; setPadding(px(8), px(6), px(8), px(6)); setOnClickListener { tol = when (tol) { 6 -> 12; 12 -> 20; 20 -> 30; else -> 6 }; text = "±$tol%" } }.also { tolText = it }
        bottom.addView(tv)
        bottom.addView(TextView(svc).apply {
            text = "✓ Save"; setTextColor(0xFF000000.toInt()); textSize = 14f; gravity = Gravity.CENTER; setPadding(px(12), px(4), px(12), px(4))
            background = GradientDrawable().apply { cornerRadius = px(16).toFloat(); setColor(0xFF8C9EFF.toInt()) }
            setOnClickListener {
                if (picked == 0) { Toast.makeText(svc, "Move the ring over a color first", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                val pkg = if (target == "*") foreground() else target
                if (pkg.isEmpty() || pkg == svc.packageName) { Toast.makeText(svc, "Open the app you want to change first", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                RecolorRules.add(sp, pkg, picked, to, tol)
                val name = try { svc.packageManager.getApplicationLabel(svc.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { pkg }
                Toast.makeText(svc, "Saved for $name", Toast.LENGTH_SHORT).show()
                hide()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(6) })
        box.addView(bottom)
        val prompt = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; setPadding(0, px(6), 0, 0) }
        promptText = TextView(svc).apply { setTextColor(Color.WHITE); textSize = 13f; typeface = android.graphics.Typeface.MONOSPACE }
        prompt.addView(promptText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        prompt.addView(TextView(svc).apply {
            text = "Yes"; setTextColor(0xFF000000.toInt()); textSize = 14f; setPadding(px(12), px(4), px(12), px(4))
            background = GradientDrawable().apply { cornerRadius = px(16).toFloat(); setColor(0xFF8C9EFF.toInt()) }
            setOnClickListener { saveRule() }
        })
        prompt.addView(TextView(svc).apply { text = "No"; setTextColor(Color.WHITE); textSize = 14f; setPadding(px(12), px(4), px(12), px(4)); setOnClickListener { promptBox?.visibility = View.GONE } })
        box.addView(prompt)
        promptBox = prompt
        // the app's own colours (its resources, whites included): tap one to change it. Works for any app, whatever its names are.
        val scroll = android.widget.HorizontalScrollView(svc)
        val appColours = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(6), 0, 0) }
        scroll.addView(appColours)
        box.addView(scroll)
        appRow = appColours
        loadAppColours()
        val row = box
        val lp = WindowManager.LayoutParams(px(250), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = px(88) }
        try { wm.addView(row, lp); bar = row } catch (_: Throwable) { return }

        val size = px(72)
        val r = RingView(svc)
        val rp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = svc.resources.displayMetrics.widthPixels / 2 - size / 2; y = svc.resources.displayMetrics.heightPixels / 3 }
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0
        r.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = rp.x; sy = rp.y }
                MotionEvent.ACTION_MOVE -> { rp.x = sx + (e.rawX - dx).toInt(); rp.y = sy + (e.rawY - dy).toInt(); try { wm.updateViewLayout(r, rp) } catch (_: Throwable) { } }
                MotionEvent.ACTION_UP -> sample()
            }
            true
        }
        try { wm.addView(r, rp); ring = r; ringLp = rp } catch (_: Throwable) { }
        ui.postDelayed({ sample() }, 400)
    }

    /** Reads the app's colours from its APK on a background thread, then lists them as dots in the bar. */
    private fun loadAppColours() {
        val pkg = if (target == "*") foreground() else target
        if (pkg.isEmpty() || pkg == svc.packageName) return
        Thread {
            val colours = try {
                Arsc.scan(svc.packageManager.getApplicationInfo(pkg, 0).sourceDir, all = true).candidates
            } catch (_: Throwable) { emptyList() }
            ui.post {
                val row = appRow ?: return@post
                row.removeAllViews()
                if (colours.isEmpty()) { row.addView(TextView(svc).apply { text = "No colours found in this app"; setTextColor(0xFF9A9AA6.toInt()); textSize = 12f }); return@post }
                // whites and light colours first: they are the ones that usually need to change
                colours.sortedByDescending { lumOf(it.color) }.take(80).forEach { c ->
                    row.addView(dot(c.color, 26).apply {
                        setOnClickListener { picked = c.color or -0x1000000; tol = 6; paint(); showPrompt() }
                    })
                }
            }
        }.start()
    }

    /** After a dropper pick: the bar asks whether this colour should change, and Yes saves it at once. */
    private fun showPrompt() {
        if (picked == 0) return
        val to2 = if (to == 0xFF000000.toInt()) "black" else "#%06X".format(to and 0xFFFFFF)
        promptText?.text = "#%06X → %s ?".format(picked and 0xFFFFFF, to2)
        promptBox?.visibility = View.VISIBLE
    }

    /** Saves the picked colour for the open app, with the target and tolerance the bar shows. The bar stays open for the next pick. */
    private fun saveRule() {
        if (picked == 0) return
        val pkg = if (target == "*") foreground() else target
        if (pkg.isEmpty() || pkg == svc.packageName) { Toast.makeText(svc, "Open the app you want to change first", Toast.LENGTH_SHORT).show(); return }
        RecolorRules.add(sp, pkg, picked, to, tol)
        promptBox?.visibility = View.GONE
        Toast.makeText(svc, "Saved. Reopen the app to see it.", Toast.LENGTH_SHORT).show()
    }

    /** The chosen target dot stays full size and bright, the others are dimmed. */
    private fun dotsShown(row: LinearLayout, chosen: View) {
        for (i in 0 until row.childCount) { val v = row.getChildAt(i); if (v is View && v.background is GradientDrawable && v !is TextView) v.alpha = if (v === chosen) 1f else .4f }
    }

    /** Reads the color at the centre of the ring: the ring hides, the screen is captured, the ring comes back. */
    private fun sample() {
        val r = ring ?: return; val lp = ringLp ?: return
        val cx = lp.x + lp.width / 2; val cy = lp.y + lp.height / 2
        r.visibility = View.INVISIBLE
        ui.postDelayed({
            try {
                svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(res: AccessibilityService.ScreenshotResult) {
                        val hb = res.hardwareBuffer
                        val bmp = try { Bitmap.wrapHardwareBuffer(hb, res.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { null }
                        try { hb.close() } catch (_: Throwable) { }
                        val dm = svc.resources.displayMetrics
                        if (bmp != null) {
                            val x = (cx * bmp.width / dm.widthPixels.toFloat()).toInt().coerceIn(0, bmp.width - 1)
                            val y = (cy * bmp.height / dm.heightPixels.toFloat()).toInt().coerceIn(0, bmp.height - 1)
                            // the average of a small patch: one pixel is noisy at the edges, and a screenshot's colour can be a shade off
                            var sr = 0; var sg = 0; var sb = 0; var n = 0
                            for (dy in -2..2) for (dx in -2..2) {
                                val px = (x + dx).coerceIn(0, bmp.width - 1); val py = (y + dy).coerceIn(0, bmp.height - 1)
                                val c = bmp.getPixel(px, py); sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255; n++
                            }
                            picked = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                            // a light colour (white, near-white): a wider tolerance, so a shade off still matches the app's own white
                            tol = if (lumOf(picked) > .85f) 20 else 12
                            bmp.recycle()
                        }
                        ui.post { r.visibility = View.VISIBLE; paint(); showPrompt() }
                    }
                    override fun onFailure(code: Int) { ui.post { r.visibility = View.VISIBLE; Toast.makeText(svc, "Could not read the screen (code $code)", Toast.LENGTH_SHORT).show() } }
                })
            } catch (_: Throwable) { r.visibility = View.VISIBLE }
        }, 90)
    }

    private fun paint() {
        (swatch?.background as? GradientDrawable)?.setColor(picked)
        hex?.text = "#%06X".format(picked and 0xFFFFFF)
    }

    fun hide() {
        bar?.let { try { wm.removeView(it) } catch (_: Throwable) { } }; bar = null
        ring?.let { try { wm.removeView(it) } catch (_: Throwable) { } }; ring = null
    }
}
