package com.umutk.blackout

import android.content.pm.PackageManager
import java.util.concurrent.TimeUnit

/** How commands are run with shell-level power: root (su) or Shizuku. Overlays can only be fabricated by root or the shell user. */
object Privilege {
    enum class Mode { None, Root, Shizuku }

    class Out(val code: Int, val text: String)

    @Volatile var mode = Mode.None; private set
    @Volatile var detail = ""; private set

    private fun shizukuOn(): Boolean = try {
        rikka.shizuku.Shizuku.pingBinder() && rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    fun shizukuRunning(): Boolean = try { rikka.shizuku.Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun requestShizuku() { try { rikka.shizuku.Shizuku.requestPermission(41) } catch (_: Throwable) { } }

    /** What the last root attempt answered, so the user sees why it failed. */
    @Volatile var rootNote = ""; private set

    /** Asks for root explicitly (this is what makes the root manager show its grant popup). */
    fun tryRoot(): Boolean {
        val r = try { runRoot("id") } catch (e: Throwable) { null }
        rootNote = when {
            r == null -> "su could not be started (no root on this phone, or the root app hides su)"
            r.text.contains("uid=0") -> "root works"
            else -> "su answered: " + r.text.take(160).ifBlank { "nothing (denied?)" }
        }
        if (r != null && r.text.contains("uid=0")) { mode = Mode.Root; detail = "root"; return true }
        return false
    }

    /** Looks at what is available now (root first, then Shizuku) and remembers it. [prefer] forces one of them. */
    fun detect(prefer: String = "auto"): Mode {
        if (prefer != "shizuku" && tryRoot()) return mode
        if (prefer != "root" && shizukuOn()) { mode = Mode.Shizuku; detail = "Shizuku"; return mode }
        mode = Mode.None
        detail = when {
            prefer == "root" -> rootNote
            shizukuRunning() -> "Shizuku is running but BlackOut has no permission yet. $rootNote"
            else -> "$rootNote; Shizuku is not running"
        }
        return mode
    }

    private fun collect(p: Process): Out {
        val out = StringBuilder()
        val t = Thread { try { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } catch (_: Exception) { } }.also { it.start() }
        val err = StringBuilder()
        val t2 = Thread { try { p.errorStream.bufferedReader().forEachLine { err.appendLine(it) } } catch (_: Exception) { } }.also { it.start() }
        if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); return Out(-1, "timed out") }
        t.join(2000); t2.join(2000)
        return Out(p.exitValue(), (out.toString() + err.toString()).trim())
    }

    private fun runRoot(cmd: String): Out? = try {
        collect(Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)))
    } catch (_: Exception) { null }

    /** Shizuku keeps newProcess private: reach it by reflection (the usual way for apps without a user service). */
    private fun runShizuku(cmd: String): Out? = try {
        val m = rikka.shizuku.Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        collect(p)
    } catch (e: Throwable) { Out(-1, "Shizuku: " + (e.cause?.message ?: e.message)) }

    /** Runs a shell script with the detected power. */
    fun run(script: String): Out = when (mode) {
        Mode.Root -> runRoot(script) ?: Out(-1, "su failed")
        Mode.Shizuku -> runShizuku(script) ?: Out(-1, "Shizuku failed")
        Mode.None -> Out(-1, "no root / Shizuku")
    }
}
