package com.umutk.blackout

import android.graphics.Canvas
import android.graphics.Paint

/**
 * Jetpack Compose (and any app that draws straight on android.graphics.Canvas): Compose colour constants are inlined into the app's
 * code, so they cannot be hooked at construction. What reaches the screen is a Paint colour on a Canvas draw call, so the Paint
 * colour is swapped through [Grey.fix] for the length of that call and put back right after it.
 * Text is not touched (drawText is not hooked). Off unless the "compose_dark" preference is on.
 * shortcut: unverified on a device; Paint is assumed to be copied at record time so restoring it after the call is safe, check on Android 12+ before enabling for all apps.
 */
object ComposeDark {
    /** Paints swapped by the current draw call; a stack, because draw calls can nest. */
    private val swapped = ThreadLocal.withInitial { ArrayList<Pair<Paint, Int>>() }

    private val drawNames = listOf("drawRect", "drawRoundRect", "drawPaint", "drawPath", "drawCircle", "drawOval", "drawArc")

    fun install(limit: Int, keep: Float, tint: Int, soft: Boolean) {
        val h = object : Hook() {
            override fun beforeHookedMethod(param: HookParam) {
                val paint = param.args.firstOrNull { it is Paint } as? Paint ?: return
                val c = paint.color
                swapped.get()!!.add(paint to c)
                val f = Grey.fix(c, limit, keep, tint, soft)
                if (f != c) { paint.color = f; Counters.greys.incrementAndGet() }
            }

            override fun afterHookedMethod(param: HookParam) {
                if (param.args.none { it is Paint }) return
                val stack = swapped.get()!!
                if (stack.isEmpty()) return
                val (paint, c) = stack.removeAt(stack.size - 1)
                paint.color = c
            }
        }
        for (name in drawNames) {
            try { Hooks.all(Canvas::class.java, name, h) } catch (t: Throwable) { Hooks.log("BlackOut: compose dark $name $t") }
        }
    }
}
