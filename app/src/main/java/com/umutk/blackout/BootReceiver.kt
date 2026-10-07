package com.umutk.blackout

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a restart the screen filter (Dark pages) is gone: switch it on again if it was on. Overlays and the LSPosed module survive a restart by themselves. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        val sp = c.getSharedPreferences("blackout", Context.MODE_PRIVATE)
        if (!sp.getBoolean("pg_on", false)) return
        val pending = goAsync()
        Thread {
            try {
                Thread.sleep(25_000)   // root is not ready right at boot
                if (Privilege.detect(sp.getString("prefer", "auto") ?: "auto") != Privilege.Mode.None) PageDark.apply(PageDark.fromPrefs(sp))
            } finally { pending.finish() }
        }.start()
    }
}
