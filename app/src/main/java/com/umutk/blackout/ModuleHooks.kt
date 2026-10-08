package com.umutk.blackout

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrixColorFilter
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.view.View
import android.webkit.WebView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.WeakHashMap

private fun hook(f: () -> Unit) { try { f() } catch (t: Throwable) { XposedBridge.log("BlackOut: $t") } }

/** The module's colour rule, shared with the preview in the app: a dark neutral grey no brighter than [limit] becomes black ([keep] of it stays). */
object Grey {
    fun fix(c: Int, limit: Int, keep: Float, tint: Int = 0): Int {
        if (((c ushr 24) and 255) != 255) return c
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        val mx = maxOf(r, g, b)
        if (mx == 0 || mx > limit || mx - minOf(r, g, b) > 24) return c
        return Overlays.scaled(c, keep, tint)
    }
}

/**
 * Force dark: Android's own engine (the one behind "Override force-dark" in developer options) switched on inside one app,
 * even when the app has no dark theme or opts out. White surfaces turn dark, dark text and icons turn light, photos stay.
 * Its dark is about #1C1C1C; pure black comes from the black level the app page can add on top.
 */
object ForceDarkHooks {
    fun install() {
        fun hookWith(count: Boolean) = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val a = param.args
                if (a.isEmpty()) return
                when (val v = a[0]) {
                    is Boolean -> { if (!v && count) Counters.forced.incrementAndGet(); a[0] = true }
                    is Int -> if (v == 0) { a[0] = 1; if (count) Counters.forced.incrementAndGet() }
                }
            }
        }
        val on = hookWith(false)
        // setForceDark(boolean) up to Android 14, setForceDark(int type) after it
        hook { XposedBridge.hookAllMethods(HardwareRenderer::class.java, "setForceDark", hookWith(true)) }
        // views and render nodes that opt out are let in again
        hook { XposedBridge.hookAllMethods(View::class.java, "setForceDarkAllowed", on) }
        hook { XposedBridge.hookAllMethods(RenderNode::class.java, "setForceDarkAllowed", on) }
        // web pages inside the app: ask WebView for its own dark rendering (on new Android versions a light app theme can still veto it)
        hook {
            XposedBridge.hookAllConstructors(WebView::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val s = (param.thisObject as? WebView)?.settings ?: return
                    try { s.javaClass.getMethod("setAlgorithmicDarkeningAllowed", Boolean::class.javaPrimitiveType).invoke(s, true) } catch (_: Throwable) { }
                    try { s.javaClass.getMethod("setForceDark", Int::class.javaPrimitiveType).invoke(s, 2) } catch (_: Throwable) { }
                }
            })
        }
    }
}

/**
 * Pages only: inside one app (Samsung Notes, PDF readers) big light pictures (PDF pages, paper, ink layers) and big light flat fills
 * get light and dark swapped with the Dark pages colours, and dark text turns light so it stays readable on them. Toolbars, menus and small icons are left alone.
 * Only drawing that goes through Android's Canvas is reached; pages an app paints with its own GPU code are not.
 */
object PageHooks {
    private class Undo(val paint: Paint, val filter: ColorFilter?, val color: Int, val recolored: Boolean)

    /** One entry per hooked call in progress on this thread (null when nothing was changed), so nested calls restore the right paint. */
    private val undo = ThreadLocal.withInitial { ArrayList<Undo?>() }
    private val seen = WeakHashMap<Bitmap, Long>()   // bitmap -> generationId shl 1 | isPage

    private fun lum(c: Int) = (.299f * ((c shr 16) and 255) + .587f * ((c shr 8) and 255) + .114f * (c and 255)) / 255f
    private fun isLight(c: Int) = ((c ushr 24) and 255) >= 200 && lum(c) > .6f

    /** A page is mostly opaque and light. A mostly see-through bitmap is taken as an ink layer: its dark strokes are turned light too. */
    private fun pageLike(b: Bitmap): Boolean {
        if (b.isRecycled || b.config == Bitmap.Config.HARDWARE) return false
        val gen = b.generationId.toLong()
        synchronized(seen) { seen[b]?.let { if (it shr 1 == gen) return (it and 1L) == 1L } }
        val w = b.width; val h = b.height
        var n = 0; var opaque = 0; var light = 0
        for (i in 1..7) for (j in 1..7) {
            val c = try { b.getPixel(w * i / 8, h * j / 8) } catch (_: Throwable) { return false }
            n++
            if ((c ushr 24) < 128) continue
            opaque++
            if (lum(c) > .6f) light++
        }
        val page = if (opaque * 2 >= n) light * 10 >= opaque * 6 else true
        synchronized(seen) { seen[b] = (gen shl 1) or (if (page) 1L else 0L) }
        return page
    }

    private fun bitmapSize(args: Array<Any?>, b: Bitmap): Pair<Float, Float> {
        (args.firstOrNull { it is Matrix } as Matrix?)?.let { m ->
            val r = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()); m.mapRect(r); return r.width() to r.height()
        }
        return when (val d = args.lastOrNull { it is Rect || it is RectF }) {
            is Rect -> d.width().toFloat() to d.height().toFloat()
            is RectF -> d.width() to d.height()
            else -> b.width.toFloat() to b.height.toFloat()
        }
    }

    private fun rectSize(args: Array<Any?>): Pair<Float, Float> {
        when (val a = args.firstOrNull()) {
            is Rect -> return a.width().toFloat() to a.height().toFloat()
            is RectF -> return a.width() to a.height()
        }
        if (args.size < 4) return 0f to 0f
        val f = args.take(4).map { (it as? Float) ?: return 0f to 0f }
        return (f[2] - f[0]) to (f[3] - f[1])
    }

    fun install(cfg: PageDark.Cfg, smart: Boolean = false) {
        val m = PageDark.colorMatrix(cfg)
        val filter = ColorMatrixColorFilter(m)
        fun ch(row: Int, r: Int, g: Int, b: Int) = (m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 4]).toInt().coerceIn(0, 255)
        fun map(c: Int): Int {
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            return (c and -0x1000000) or (ch(0, r, g, b) shl 16) or (ch(1, r, g, b) shl 8) or ch(2, r, g, b)
        }
        val screen = Resources.getSystem().displayMetrics

        val h = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                undo.get()!!.add(change(param))
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val stack = undo.get()!!
                val u = (if (stack.isEmpty()) null else stack.removeAt(stack.size - 1)) ?: return
                if (u.recolored) u.paint.color = u.color else u.paint.colorFilter = u.filter
            }

            private fun change(param: MethodHookParam): Undo? {
                val canvas = param.thisObject as? Canvas ?: return null
                val method = param.method as? Method ?: return null
                val types = method.parameterTypes
                val args = param.args
                val cw = canvas.width; val chh = canvas.height
                // smart mode also takes cards and rows; icons and chips (small fills) always stay
                fun big(w: Float, hh: Float) = cw > 0 && chh > 0 && (if (smart) w >= cw * .22f && hh >= chh * .02f else w >= cw * .6f && hh >= chh * .3f)
                when (method.name) {
                    "drawText", "drawTextRun", "drawTextOnPath", "drawPosText" -> {
                        // text on a page that turned dark has to turn light too, or it disappears
                        val pi = types.indexOf(Paint::class.java); if (pi < 0) return null
                        val p = args[pi] as? Paint ?: return null
                        val c = p.color
                        if (p.shader != null || ((c ushr 24) and 255) < 128 || lum(c) >= .4f) return null
                        p.color = map(c)
                        Counters.texts.incrementAndGet()
                        return Undo(p, null, c, true)
                    }
                    "drawColor" -> {
                        if (types.firstOrNull() == Int::class.javaPrimitiveType) { val c = args[0] as Int; if (isLight(c)) { args[0] = map(c); Counters.fills.incrementAndGet() } }
                        return null
                    }
                    "drawBitmap" -> {
                        if (smart) return null // photos and logos are left alone
                        val bmp = args.firstOrNull() as? Bitmap ?: return null
                        val pi = types.indexOf(Paint::class.java); if (pi < 0) return null
                        val (w, hh) = bitmapSize(args, bmp)
                        val large = big(w, hh) || (bmp.width >= screen.widthPixels * .5f && bmp.height >= screen.heightPixels * .25f)
                        if (!large || !pageLike(bmp)) return null
                        val p = args[pi] as Paint?
                        if (p == null) { args[pi] = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = filter }; return null }
                        if (p.colorFilter != null) return null
                        p.colorFilter = filter
                        Counters.fills.incrementAndGet()
                        return Undo(p, null, 0, false)
                    }
                    else -> {   // drawPaint, drawRect, drawRoundRect: big, light, flat fills
                        val pi = types.indexOf(Paint::class.java); if (pi < 0) return null
                        val p = args[pi] as? Paint ?: return null
                        if (p.shader != null || p.style != Paint.Style.FILL || !isLight(p.color)) return null
                        if (method.name != "drawPaint") { val (w, hh) = rectSize(args); if (!big(w, hh)) return null }
                        val old = p.color
                        p.color = map(old)
                        Counters.fills.incrementAndGet()
                        return Undo(p, null, old, true)
                    }
                }
            }
        }
        val classes = listOfNotNull(Canvas::class.java, XposedHelpers.findClassIfExists("android.graphics.BaseRecordingCanvas", null))
        val names = listOf("drawBitmap", "drawColor", "drawPaint", "drawRect", "drawRoundRect", "drawText", "drawTextRun", "drawTextOnPath", "drawPosText")
        for (k in classes) for (n in names) hook { XposedBridge.hookAllMethods(k, n, h) }
    }
}

/**
 * A different way for apps that draw their pages with their own view (Samsung Notes, readers): the views you pick get a colour filter
 * (Android 12+ RenderEffect) that swaps light and dark with the Dark pages colours, children included. Nothing else in the app changes.
 */
object ViewEffectHooks {
    fun install(cfg: PageDark.Cfg, classes: Set<String>) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        val filter = ColorMatrixColorFilter(PageDark.colorMatrix(cfg))
        hook {
            XposedBridge.hookAllMethods(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject as? View ?: return
                    var k: Class<*>? = v.javaClass
                    while (k != null && k != View::class.java) { if (k.name in classes) break; k = k.superclass }
                    if (k == null || k == View::class.java) return
                    try { v.setRenderEffect(android.graphics.RenderEffect.createColorFilterEffect(filter)) } catch (t: Throwable) { XposedBridge.log("BlackOut: view effect $t") }
                }
            })
        }
    }
}


/**
 * Web pages inside an app (the Google app's results, mail bodies, in-app browsers) are drawn by Chromium, not through Android's colour calls,
 * so the colour hooks never see them. This runs a small script in every WebView of the app: every element whose background is an opaque dark
 * grey up to the limit becomes #000, now and when the page changes. Light pages are left alone (force dark makes them dark first).
 */
object WebBlack {
    private val running = java.util.WeakHashMap<WebView, Boolean>()

    private const val SCRIPT = """(function(){
if(window.__boBlack)return;window.__boBlack=1;var LIM=@LIM@;
function dark(c){var m=/rgba?\((\d+)[ ,]+(\d+)[ ,]+(\d+)(?:[ ,\/]+([\d.]+%?))?/.exec(c);if(!m)return false;
var r=+m[1],g=+m[2],b=+m[3],a=m[4]===undefined?1:parseFloat(m[4]);if(m[4]&&m[4].indexOf('%')>0)a/=100;if(a<0.9)return false;
var mx=Math.max(r,g,b);return mx>0&&mx<=LIM&&mx-Math.min(r,g,b)<=24;}
function fix(e){try{if(dark(getComputedStyle(e).backgroundColor))e.style.setProperty('background-color','#000','important');}catch(x){}}
var q=[];function add(n){if(n.nodeType===1){q.push(n);var d=n.querySelectorAll?n.querySelectorAll('*'):[];for(var i=0;i<d.length;i++)q.push(d[i]);}}
function work(){var t=Date.now();while(q.length&&Date.now()-t<8)fix(q.pop());if(q.length)setTimeout(work,30);}
add(document.documentElement);work();
new MutationObserver(function(ms){for(var i=0;i<ms.length;i++){var m=ms[i];if(m.type==='childList'){for(var j=0;j<m.addedNodes.length;j++)add(m.addedNodes[j]);}else if(m.target.nodeType===1)q.push(m.target);}work();})
.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['class','style']});
})();"""

    fun install(limit: Int) {
        val js = SCRIPT.replace("@LIM@", limit.toString())
        hook {
            XposedBridge.hookAllMethods(WebView::class.java, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val w = param.thisObject as? WebView ?: return
                    synchronized(running) { if (running[w] == true) return; running[w] = true }
                    // a page change wipes the script, so it is offered again every moment while the view is on screen (it does nothing if it is already there)
                    val tick = object : Runnable {
                        override fun run() {
                            if (!w.isAttachedToWindow) { synchronized(running) { running.remove(w) }; return }
                            try { w.evaluateJavascript(js, null) } catch (_: Throwable) { }
                            w.postDelayed(this, 1500)
                        }
                    }
                    w.postDelayed(tick, 700)
                }
            })
        }
    }
}

/**
 * Colour rules for one app, set from the Colours page or the floating picker: every flat fill (and text) that is close to a chosen colour
 * is drawn in another one. When a light colour is turned dark, dark text turns light with it so it stays readable.
 */
object Recolor {
    class Rule(val from: Int, val to: Int, val tol: Int)

    /** "RRGGBB>RRGGBB>percent;..." as stored by the app. */
    fun parse(s: String?): List<Rule> = s.orEmpty().split(';').mapNotNull { p ->
        val a = p.split('>')
        if (a.size < 3) null else try { Rule(a[0].toLong(16).toInt() or -0x1000000, a[1].toLong(16).toInt() or -0x1000000, a[2].toInt().coerceIn(0, 60) * 255 / 100) } catch (_: Throwable) { null }
    }

    fun lum(c: Int) = (.299f * ((c shr 16) and 255) + .587f * ((c shr 8) and 255) + .114f * (c and 255)) / 255f

    private fun near(a: Int, b: Int, tol: Int) =
        Math.abs(((a shr 16) and 255) - ((b shr 16) and 255)) <= tol && Math.abs(((a shr 8) and 255) - ((b shr 8) and 255)) <= tol && Math.abs((a and 255) - (b and 255)) <= tol

    fun install(rules: List<Rule>) {
        // text that is dark would vanish on a fill that turned dark
        val lightText = rules.any { lum(it.to) < .35f && lum(it.from) > .5f }
        fun map(c: Int): Int {
            if (((c ushr 24) and 255) < 8) return c
            for (r in rules) if (near(c, r.from, r.tol)) return (c and -0x1000000) or (r.to and 0xFFFFFF)
            return c
        }
        fun mapText(c: Int): Int {
            val m = map(c)
            if (m != c) return m
            return if (lightText && ((c ushr 24) and 255) >= 128 && lum(c) < .4f) (c and -0x1000000) or 0xEBEBEB else c
        }
        val undo = ThreadLocal.withInitial { ArrayList<Pair<Paint, Int>?>() }
        val h = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val stack = undo.get()!!
                val method = param.method as? Method ?: run { stack.add(null); return }
                val types = method.parameterTypes
                if (method.name == "drawColor") {
                    if (types.firstOrNull() == Int::class.javaPrimitiveType) { val c = param.args[0] as Int; val m = map(c); if (m != c) param.args[0] = m }
                    stack.add(null); return
                }
                val pi = types.indexOf(Paint::class.java)
                val p = if (pi >= 0) param.args[pi] as? Paint else null
                if (p == null || p.shader != null) { stack.add(null); return }
                val c = p.color
                val m = if (method.name.startsWith("drawText") || method.name == "drawPosText") mapText(c) else if (p.style == Paint.Style.STROKE) c else map(c)
                if (m != c) { p.color = m; stack.add(p to c) } else stack.add(null)
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                val stack = undo.get()!!
                val u = (if (stack.isEmpty()) null else stack.removeAt(stack.size - 1)) ?: return
                u.first.color = u.second
            }
        }
        val classes = listOfNotNull(Canvas::class.java, XposedHelpers.findClassIfExists("android.graphics.BaseRecordingCanvas", null))
        val names = listOf("drawColor", "drawPaint", "drawRect", "drawRoundRect", "drawCircle", "drawOval", "drawPath", "drawText", "drawTextRun", "drawTextOnPath", "drawPosText")
        for (k in classes) for (n in names) hook { XposedBridge.hookAllMethods(k, n, h) }
        // plain colour drawables and colour lookups (window and view backgrounds)
        hook {
            XposedHelpers.findAndHookMethod(android.graphics.drawable.ColorDrawable::class.java, "draw", Canvas::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val d = param.thisObject as android.graphics.drawable.ColorDrawable
                    val c = d.color; val m = map(c)
                    if (m != c) d.color = m
                }
            })
        }
        val after = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) { (param.result as? Int)?.let { val m = map(it); if (m != it) param.result = m } }
        }
        val int = Int::class.javaPrimitiveType
        hook { XposedHelpers.findAndHookMethod(Resources::class.java, "getColor", int, android.content.res.Resources.Theme::class.java, after) }
        hook { XposedHelpers.findAndHookMethod(Resources::class.java, "getColor", int, after) }
        hook { XposedHelpers.findAndHookMethod(android.content.res.TypedArray::class.java, "getColor", int, int, after) }
    }
}
