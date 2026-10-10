package com.umutk.blackout

import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/** Read by the BlackOut screen: the LSPosed module flips this to true inside BlackOut's own process, so the app can tell the module is active. */
object Status {
    @JvmStatic fun isActive(): Boolean = false
}

/**
 * The module's work, shared by both entry classes: the legacy LSPosed entry ([XposedInit]) and the modern libxposed entry ([ModernEntry]).
 * Every dark neutral grey an enabled app asks for (resources, theme attributes, color state lists, plain color drawables) is answered as black.
 * Settings come from BlackOut's own preferences (grey limit, how much of the brightness stays). Nothing is sent anywhere.
 */
object ModuleCore {
    @Volatile private var started = false

    /** Makes BlackOut's own status check answer "active" inside its own process. */
    fun statusActive(loader: ClassLoader?) {
        try {
            Hooks.oneIn(loader, "com.umutk.blackout.Status", "isActive", object : Hook() {
                override fun beforeHookedMethod(param: HookParam) { param.result = true }
            })
        } catch (_: Throwable) { }
    }

    /** Runs once per process, whichever entry gets here first, so the hooks are never installed twice. */
    fun run(pkg: String, sp: SharedPreferences) {
        synchronized(this) { if (started) return; started = true }
        if (!sp.getBoolean("lsp_on", true)) return
        if (pkg in (sp.getStringSet("lsp_off", emptySet()) ?: emptySet())) return
        val limit = sp.getFloat("limit", 56f).toInt()
        val keep = sp.getFloat("keep", 0f) / 100f
        val tint = sp.getInt("tint", 0)
        fun ticked(key: String) = pkg in (sp.getStringSet(key, emptySet()) ?: emptySet())
        val keys = try { sp.all?.size ?: -1 } catch (_: Throwable) { -1 }   // 0 = BlackOut's settings could not be read from inside this app
        Reporter.install(pkg, keys, ticked("fd_apps"), ticked("smart_apps"), ticked("pgmod_apps") || (sp.getStringSet("pgview_" + pkg, emptySet()) ?: emptySet()).isNotEmpty(), sp.getBoolean("hook_test", false))
        if (sp.getBoolean("web_black", true)) WebBlack.install(limit)
        if (ticked("fd_apps")) ForceDarkHooks.install()
        if (ticked("smart_apps")) PageHooks.install(PageDark.Cfg(0x000000, 0xEBEBEB, true), smart = true)
        else if (ticked("pgmod_apps")) PageHooks.install(PageDark.from({ k, d -> sp.getFloat(k, d) }, sp.getBoolean("pg_hue", true)))
        (sp.getStringSet("pgview_" + pkg, emptySet()) ?: emptySet()).takeIf { it.isNotEmpty() }
            ?.let { ViewEffectHooks.install(PageDark.from({ k, d -> sp.getFloat(k, d) }, sp.getBoolean("pg_hue", true)), it) }

        (Recolor.builtin(pkg) + Recolor.parse(sp.getString("rc_" + pkg, null))).takeIf { it.isNotEmpty() }?.let { rules ->
            Recolor.install(rules)
            // a light color turned dark: apps that paint their page as a picture (Samsung Notes, readers) are not reached by flat-fill rules, so big light pictures, fills and dark text follow the same target color
            val page = rules.firstOrNull { Recolor.lum(it.from) > .5f && Recolor.lum(it.to) < .35f }
            if (page != null && !ticked("smart_apps") && !ticked("pgmod_apps")) PageHooks.install(PageDark.Cfg(page.to and 0xFFFFFF, 0xEBEBEB, true))
        }

        fun fix(c: Int): Int { val o = Grey.fix(c, limit, keep, tint); if (o != c) Counters.greys.incrementAndGet(); return o }
        val after = object : Hook() {
            override fun afterHookedMethod(param: HookParam) { (param.result as? Int)?.let { param.result = fix(it) } }
        }
        fun hook(f: () -> Unit) { try { f() } catch (t: Throwable) { Hooks.log("BlackOut: $t") } }
        val int = Integer.TYPE
        hook { Hooks.one(Resources::class.java, "getColor", int, Resources.Theme::class.java, after) }
        hook { Hooks.one(Resources::class.java, "getColor", int, after) }
        hook { Hooks.one(TypedArray::class.java, "getColor", int, int, after) }
        hook { Hooks.one(ColorStateList::class.java, "getDefaultColor", after) }
        hook { Hooks.one(ColorStateList::class.java, "getColorForState", IntArray::class.java, int, after) }
        hook {
            Hooks.one(ColorDrawable::class.java, "draw", Canvas::class.java, object : Hook() {
                override fun beforeHookedMethod(param: HookParam) {
                    val d = param.thisObject as ColorDrawable
                    val c = d.color; val f = fix(c)
                    if (f != c) d.color = f
                }
            })
        }
    }
}

/** Legacy LSPosed entry (xposed_init). Reads the settings with XSharedPreferences. */
class XposedInit : IXposedHookLoadPackage {
    override fun handleLoadPackage(p: XC_LoadPackage.LoadPackageParam) {
        if (p.packageName == "com.umutk.blackout") { ModuleCore.statusActive(p.classLoader); return }
        val sp = XSharedPreferences("com.umutk.blackout", "blackout")
        sp.reload()
        // the real XSharedPreferences implements SharedPreferences; the local compile-time stub does not, hence the cast
        ModuleCore.run(p.packageName, sp as android.content.SharedPreferences)
    }
}

/** Modern libxposed entry (META-INF/xposed/java_init.list). Reads the settings from the remote preferences LSPosed keeps for BlackOut. */
class ModernEntry : XposedModule() {
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName == "com.umutk.blackout") { Hooks.modern = this; ModuleCore.statusActive(param.classLoader); return }
        // no settings have reached this process: step aside, so the legacy entry (which has always worked here) does the job as before
        val remote = getRemotePreferences("blackout")
        if (remote.all.isEmpty()) return
        Hooks.modern = this
        ModuleCore.run(param.packageName, remote)
    }
}
