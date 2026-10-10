package com.umutk.blackout

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger

/** What the LSPosed module did inside one app, counted while it runs and sent to BlackOut so the app page can show it. */
object Counters {
    val forced = AtomicInteger()   // windows where Android's force dark was switched on
    val fills = AtomicInteger()    // big light areas repainted
    val texts = AtomicInteger()    // dark text turned light
    val greys = AtomicInteger()    // dark greys turned black
}

/**
 * Module side: a few seconds after an app starts, and then every 15 seconds while it runs, the module tells BlackOut it is there
 * (an explicit broadcast to BlackOut only; numbers, no content). If an app never reports, the module is not active in it.
 */
object Reporter {
    const val ACTION = "com.umutk.blackout.HOOK_STATUS"

    fun install(pkg: String, prefsKeys: Int, fd: Boolean, smart: Boolean, pages: Boolean, testToast: Boolean = false) {
        try {
            Hooks.all(Application::class.java, "onCreate", object : Hook() {
                override fun afterHookedMethod(param: HookParam) {
                    val app = param.thisObject as? Application ?: return
                    val h = Handler(Looper.getMainLooper())
                    // a visible sign that the module runs inside this app (no logcat needed)
                    if (testToast) h.postDelayed({ try { android.widget.Toast.makeText(app, "BlackOut module: active here", android.widget.Toast.LENGTH_LONG).show() } catch (_: Throwable) { } }, 2_500)
                    val tick = object : Runnable {
                        override fun run() {
                            try {
                                app.sendBroadcast(Intent(ACTION).setPackage("com.umutk.blackout")
                                    .putExtra("pkg", pkg).putExtra("keys", prefsKeys)
                                    .putExtra("fd", fd).putExtra("smart", smart).putExtra("pages", pages)
                                    .putExtra("forced", Counters.forced.get()).putExtra("fills", Counters.fills.get())
                                    .putExtra("texts", Counters.texts.get()).putExtra("greys", Counters.greys.get())
                                    .putExtra("sdk", android.os.Build.VERSION.SDK_INT))
                            } catch (_: Throwable) { }
                            h.postDelayed(this, 15_000)
                        }
                    }
                    h.postDelayed(tick, 4_000)
                }
            })
        } catch (t: Throwable) { Hooks.log("BlackOut: reporter $t") }
    }
}

/** BlackOut side: keeps the last report of every app ("package" to "time|keys|fd|smart|pages|forced|fills|texts|greys|sdk"). */
class HookReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Reporter.ACTION) return
        val pkg = i.getStringExtra("pkg")?.takeIf { it.length < 200 && it.all { ch -> ch.isLetterOrDigit() || ch == '.' || ch == '_' } } ?: return
        val v = listOf(System.currentTimeMillis(), i.getIntExtra("keys", -1), i.getBooleanExtra("fd", false), i.getBooleanExtra("smart", false), i.getBooleanExtra("pages", false),
            i.getIntExtra("forced", 0), i.getIntExtra("fills", 0), i.getIntExtra("texts", 0), i.getIntExtra("greys", 0), i.getIntExtra("sdk", 0)).joinToString("|")
        c.getSharedPreferences("hookstatus", Context.MODE_PRIVATE).edit().putString(pkg, v).apply()
    }
}

/** One parsed report. */
class HookReport(val at: Long, val keys: Int, val fd: Boolean, val smart: Boolean, val pages: Boolean, val forced: Int, val fills: Int, val texts: Int, val greys: Int, val sdk: Int) {
    companion object {
        fun read(c: Context, pkg: String): HookReport? {
            val p = (c.getSharedPreferences("hookstatus", Context.MODE_PRIVATE).getString(pkg, null) ?: return null).split('|')
            if (p.size < 10) return null
            return HookReport(p[0].toLong(), p[1].toInt(), p[2].toBoolean(), p[3].toBoolean(), p[4].toBoolean(), p[5].toInt(), p[6].toInt(), p[7].toInt(), p[8].toInt(), p[9].toInt())
        }
    }
}
