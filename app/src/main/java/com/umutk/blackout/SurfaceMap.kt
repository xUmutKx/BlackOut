package com.umutk.blackout

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * Opt-in drawing-level "white and grey surfaces go black". It sits on the Canvas calls that fill a rectangle, a rounded rectangle,
 * the whole canvas, or a colour. Only opaque, neutral, light fills that cover a big part of the canvas turn black; small chips and
 * icons stay. Text is never made lighter, so near-white text cannot vanish. Dark text drawn inside an area that was just turned
 * black is lightened, so it stays readable. Off by default: enabled by the pref key [PREF].
 */
object SurfaceMap {
    const val PREF = "surf_black"

    private const val DARK_TEXT_LUM = .4f

    private class Undo(val paint: Paint, val color: Int)

    /** One entry per hooked call in progress (null when nothing changed), so nested calls restore the right colour. */
    private val undo = ThreadLocal.withInitial { ArrayList<Undo?>() }

    /** Areas turned black, per canvas, so dark text inside them can be found. */
    private val blackened = WeakHashMap<Canvas, ArrayList<RectF>>()

    private fun lum(c: Int) = (.299f * ((c shr 16) and 255) + .587f * ((c shr 8) and 255) + .114f * (c and 255)) / 255f

    private fun neutral(c: Int): Boolean {
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        return maxOf(r, g, b) - minOf(r, g, b) <= 16
    }

    /** Opaque, neutral and light: white and light grey. Dark greys are Grey.fix's job. */
    private fun lightFill(c: Int) = ((c ushr 24) and 255) == 255 && neutral(c) && lum(c) >= .55f

    private fun remember(c: Canvas, r: RectF) = synchronized(blackened) {
        val list = blackened.getOrPut(c) { ArrayList() }
        // shortcut: list is cleared at 128 areas, so dark text in an old area of a long frame may stay dark; upgrade: clear per frame
        if (list.size >= 128) list.clear()
        list.add(RectF(r))
    }

    private fun inside(c: Canvas, x: Float, y: Float) = synchronized(blackened) {
        blackened[c]?.any { x >= it.left - 2 && x <= it.right + 2 && y >= it.top - 2 && y <= it.bottom + 2 } ?: false
    }

    private val h = object : Hook() {
        override fun beforeHookedMethod(param: HookParam) {
            undo.get()!!.add(change(param))
        }

        override fun afterHookedMethod(param: HookParam) {
            val stack = undo.get()!!
            val u = (if (stack.isEmpty()) null else stack.removeAt(stack.size - 1)) ?: return
            u.paint.color = u.color
        }

        private fun change(param: HookParam): Undo? {
            val canvas = param.thisObject as? Canvas ?: return null
            val method = param.method as? Method ?: return null
            val types = method.parameterTypes
            val args = param.args
            val cw = canvas.width.toFloat(); val ch = canvas.height.toFloat()
            if (cw <= 0f || ch <= 0f) return null
            val floatType = Float::class.javaPrimitiveType

            when (method.name) {
                "drawColor" -> {
                    // a whole-canvas colour: the light background of a page
                    if (types.firstOrNull() != Int::class.javaPrimitiveType) return null
                    val c = args[0] as Int
                    if (!lightFill(c)) return null
                    args[0] = 0xFF000000.toInt()
                    remember(canvas, RectF(0f, 0f, cw, ch))
                    return null
                }
                "drawText" -> {
                    // drawText(text, x, y, paint): only dark text that sits on an area turned black is lightened
                    if (types.size != 4 || types[1] != floatType || types[2] != floatType || types[3] != Paint::class.java) return null
                    val p = args[3] as? Paint ?: return null
                    val c = p.color
                    if (((c ushr 24) and 255) < 128 || lum(c) >= DARK_TEXT_LUM) return null
                    if (!inside(canvas, args[1] as Float, args[2] as Float)) return null
                    p.color = 0xFFEBEBEB.toInt()
                    return Undo(p, c)
                }
            }

            // drawRect, drawRoundRect, drawPaint: the paint carries the fill colour
            val pi = types.indexOf(Paint::class.java)
            val p = if (pi < 0) null else args[pi] as? Paint ?: return null
            if (p == null || p.style != Paint.Style.FILL || p.shader != null) return null
            val c = p.color
            if (!lightFill(c)) return null
            val r = when {
                args.isNotEmpty() && args[0] is RectF -> args[0] as RectF
                args.isNotEmpty() && args[0] is Rect -> RectF(args[0] as Rect)
                types.size >= 5 && types[0] == floatType -> RectF(args[0] as Float, args[1] as Float, args[2] as Float, args[3] as Float)
                else -> RectF(0f, 0f, cw, ch)   // drawPaint fills the whole canvas
            }
            // icons, chips and small buttons stay as they are
            if (r.width() < cw * .22f || r.height() < ch * .02f) return null
            p.color = 0xFF000000.toInt()
            remember(canvas, r)
            return Undo(p, c)
        }
    }

    /** Hooks Canvas and the recording canvas that real drawing goes through (the same pair Recolor and PageHooks use). */
    fun install() {
        val names = listOf("drawRect", "drawRoundRect", "drawPaint", "drawColor", "drawText")
        val classes = listOfNotNull(Canvas::class.java, Hooks.find("android.graphics.BaseRecordingCanvas"))
        for (k in classes) for (n in names) {
            try { Hooks.all(k, n, h) } catch (t: Throwable) { Hooks.log("BlackOut: SurfaceMap $t") }
        }
    }
}
