package com.umutk.blackout

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration

/**
 * Night mode for one app: each Activity is handed a context whose Configuration says "night", so the app's own values-night
 * resources load (the dark theme it already ships, usually with real dark greys and light text). The app's global config is not touched.
 * Apps with no dark theme are not changed by this; ForceDarkHooks covers those.
 * Off by default. Turn it on per app with the "night_apps" string set (package names), wired in ModuleCore.run:
 *     if (ticked("night_apps")) DarkForce.install()
 */
object DarkForce {
    fun install() {
        try {
            Hooks.all(ContextWrapper::class.java, "attachBaseContext", object : Hook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (param.thisObject !is Activity) return
                    val base = param.args[0] as? Context ?: return
                    val cfg = Configuration(base.resources.configuration)
                    cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
                    param.args[0] = base.createConfigurationContext(cfg)
                }
            })
        } catch (t: Throwable) { Hooks.log("BlackOut: DarkForce $t") }
    }
}
