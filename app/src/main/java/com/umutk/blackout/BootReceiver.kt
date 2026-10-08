package com.umutk.blackout

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a restart the screen filter (Dark pages) and the force-dark property are gone: switch them on again if they were on. Overlays and the LSPosed module survive a restart by themselves. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        val sp = c.getSharedPreferences("blackout", Context.MODE_PRIVATE)
        val pages = sp.getBoolean("pg_on", false); val fd = sp.getBoolean("fdsys_on", false)
        if (!pages && !fd) return
        val pending = goAsync()
        Thread {
            try {
                Thread.sleep(25_000)   // root is not ready right at boot
                if (Privilege.detect(sp.getString("prefer", "auto") ?: "auto") != Privilege.Mode.None) {
                    if (pages) PageDark.apply(PageDark.fromPrefs(sp))
                    if (fd) Privilege.run("setprop debug.hwui.force_dark true")
                }
            } finally { pending.finish() }
        }.start()
    }
}
