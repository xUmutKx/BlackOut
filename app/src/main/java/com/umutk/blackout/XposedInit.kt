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
        val tint = sp.getInt("tint", 0)
        fun ticked(key: String) = p.packageName in (sp.getStringSet(key, emptySet()) ?: emptySet())
        val keys = try { sp.getAll()?.size ?: -1 } catch (_: Throwable) { -1 }   // 0 = BlackOut's settings could not be read from inside this app
        Reporter.install(p.packageName, keys, ticked("fd_apps"), ticked("smart_apps"), ticked("pgmod_apps") || (sp.getStringSet("pgview_" + p.packageName, emptySet()) ?: emptySet()).isNotEmpty())
        if (sp.getBoolean("web_black", true)) WebBlack.install(limit)
        if (ticked("fd_apps")) ForceDarkHooks.install()
        if (ticked("smart_apps")) PageHooks.install(PageDark.Cfg(0x000000, 0xEBEBEB, true), smart = true)
        else if (ticked("pgmod_apps")) PageHooks.install(PageDark.from({ k, d -> sp.getFloat(k, d) }, sp.getBoolean("pg_hue", true)))
        (sp.getStringSet("pgview_" + p.packageName, emptySet()) ?: emptySet()).takeIf { it.isNotEmpty() }
            ?.let { ViewEffectHooks.install(PageDark.from({ k, d -> sp.getFloat(k, d) }, sp.getBoolean("pg_hue", true)), it) }

        Recolor.parse(sp.getString("rc_" + p.packageName, null)).takeIf { it.isNotEmpty() }?.let { rules ->
            Recolor.install(rules)
            // a light colour turned dark: apps that paint their page as a picture (Samsung Notes, readers) are not reached by flat-fill rules, so big light pictures, fills and dark text follow the same target colour
            val page = rules.firstOrNull { Recolor.lum(it.from) > .5f && Recolor.lum(it.to) < .35f }
            if (page != null && !ticked("smart_apps") && !ticked("pgmod_apps")) PageHooks.install(PageDark.Cfg(page.to and 0xFFFFFF, 0xEBEBEB, true))
        }

        fun fix(c: Int): Int { val o = Grey.fix(c, limit, keep, tint); if (o != c) Counters.greys.incrementAndGet(); return o }
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
