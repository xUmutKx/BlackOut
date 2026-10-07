package com.umutk.blackout

import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** Read by the BlackOut screen: the LSPosed module flips this to true inside BlackOut's own process, so the app can tell the module is active. */
object Status {
    @JvmStatic fun isActive(): Boolean = false
}

/**
 * LSPosed / Xposed module. Every dark neutral grey an enabled app asks for (resources, theme attributes, colour state lists, plain colour drawables)
 * is answered as black instead. It works on apps whose resource names are stripped, because it never looks at names.
 * Settings come from BlackOut's own preferences (grey limit, how much of the brightness stays). Nothing is sent anywhere.
 */
class XposedInit : IXposedHookLoadPackage {
    override fun handleLoadPackage(p: XC_LoadPackage.LoadPackageParam) {
        if (p.packageName == "com.umutk.blackout") {
            try { XposedHelpers.findAndHookMethod("com.umutk.blackout.Status", p.classLoader, "isActive", XC_MethodReplacement.returnConstant(true)) } catch (_: Throwable) { }
            return
        }
        val sp = XSharedPreferences("com.umutk.blackout", "blackout")
        sp.reload()
        if (!sp.getBoolean("lsp_on", true)) return
        if (p.packageName in (sp.getStringSet("lsp_off", emptySet()) ?: emptySet())) return
        val limit = sp.getFloat("limit", 56f).toInt()
        val keep = sp.getFloat("keep", 0f) / 100f

        fun fix(c: Int): Int {
            if (((c ushr 24) and 255) != 255) return c
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            val mx = maxOf(r, g, b)
            if (mx == 0 || mx > limit || mx - minOf(r, g, b) > 24) return c
            return Overlays.scaled(c, keep)
        }
        val after = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) { (param.getResult() as? Int)?.let { param.setResult(fix(it)) } }
        }
        fun hook(f: () -> Unit) { try { f() } catch (t: Throwable) { XposedBridge.log("BlackOut: " + t) } }
        val int = Int::class.javaPrimitiveType
        hook { XposedHelpers.findAndHookMethod(Resources::class.java, "getColor", int, Resources.Theme::class.java, after) }
        hook { XposedHelpers.findAndHookMethod(Resources::class.java, "getColor", int, after) }
        hook { XposedHelpers.findAndHookMethod(TypedArray::class.java, "getColor", int, int, after) }
        hook { XposedHelpers.findAndHookMethod(ColorStateList::class.java, "getDefaultColor", after) }
        hook { XposedHelpers.findAndHookMethod(ColorStateList::class.java, "getColorForState", IntArray::class.java, int, after) }
        hook {
            XposedHelpers.findAndHookMethod(ColorDrawable::class.java, "draw", Canvas::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val d = param.thisObject as ColorDrawable
                    val c = d.color; val f = fix(c)
                    if (f != c) d.color = f
                }
            })
        }
    }
}
