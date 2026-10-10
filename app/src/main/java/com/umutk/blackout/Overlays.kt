package com.umutk.blackout

/**
 * Turns color resources of other apps black with fabricated runtime resource overlays (what `cmd overlay fabricate` creates).
 * One overlay per resource, all named bo_<package>_<resource>, so they can be listed and removed again.
 */
object Overlays {
    private fun tag(pkg: String) = "bo_" + pkg.replace('.', '_') + "_"
    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_]"), "_")
    private fun oname(pkg: String, res: String) = tag(pkg) + safe(res)

    /** The color with every channel scaled by [keep] (0 = pure black), keeping alpha. */
    fun scaled(color: Int, keep: Float, tint: Int = 0): Int {
        val r = ((((color shr 16) and 255) * keep).toInt() + ((tint shr 16) and 255)).coerceAtMost(255)
        val g = ((((color shr 8) and 255) * keep).toInt() + ((tint shr 8) and 255)).coerceAtMost(255)
        val b = (((color and 255) * keep).toInt() + (tint and 255)).coerceAtMost(255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Android's Monet palette: the dark tones Material You apps build their dark surfaces from (tone 10 = 900, tone 20 = 800). */
    const val GLOBAL_BASE = 0xFF1A1A1A.toInt()
    fun globalNames(tinted: Boolean): List<String> {
        val n = listOf("system_neutral1_900", "system_neutral1_800", "system_neutral2_900", "system_neutral2_800")
        val t = listOf("system_accent1_900", "system_accent2_900", "system_accent2_800", "system_accent3_900", "system_accent3_800")
        return if (tinted) n + t else n
    }

    class Result(val ok: Boolean, val message: String)

    private fun one(pkg: String, res: String, color: Int): String =
        "cmd overlay fabricate --target $pkg --name ${oname(pkg, res)} $pkg:color/$res 0x1c 0x${"%08x".format(color)}"

    private fun enable(pkg: String, res: String): String {
        val n = oname(pkg, res)
        return "(cmd overlay enable --user 0 root:$n || cmd overlay enable --user 0 com.android.shell:$n)"
    }

    /**
     * Creates + enables overlays for [items] (resource name to new color) of [pkg].
     * The first one runs alone and shows Android's own answer; if it fails the rest is not even tried (an app that does not allow overlays
     * would otherwise fail hundreds of times). The rest goes in parallel batches so that a few hundred colors take seconds, not minutes.
     */
    fun apply(pkg: String, items: List<Pair<String, Int>>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result {
        if (items.isEmpty()) return Result(true, "nothing to apply")
        val (r0, c0) = items[0]
        val probe = Privilege.run("echo \"fabricate:\"; ${one(pkg, r0, c0)} 2>&1 | head -c 300; echo \"enable:\"; ${enable(pkg, r0)} 2>&1 | head -c 300; echo; echo count:; cmd overlay list --user 0 | grep '${tag(pkg)}' | grep -c '\\[x\\]'")
        val ok0 = (probe.text.lines().lastOrNull()?.trim()?.toIntOrNull() ?: 0) > 0
        if (!ok0) {
            val log = probe.text.substringBefore("count:").trim()
            val why = if (log.contains("Unable to retrieve overlay information")) "This app does not let overlays change that color (it is not overlayable). Use Dark pages or the LSPosed module for it instead."
                else "Android said:\n" + log.ifBlank { "(nothing: is 'cmd overlay' available on this Android version?)" }
            return Result(false, "No overlay is enabled. $why")
        }
        onProgress(1, items.size)
        var done = 1
        for (chunk in items.drop(1).chunked(16)) {
            val sb = StringBuilder()
            for ((res, color) in chunk) sb.append("(${one(pkg, res, color)} && ${enable(pkg, res)}) >/dev/null 2>&1 & ")
            sb.append("wait")
            Privilege.run(sb.toString())
            done += chunk.size; onProgress(done, items.size)
        }
        val made = activeCount(pkg)
        return Result(true, "$made of ${items.size} overlays active for $pkg. Close the app from recents and open it again.")
    }

    /** Disables every BlackOut overlay of [pkg] (and tries to unregister them). */
    fun remove(pkg: String): Result {
        val t = tag(pkg)
        val script = "for o in \$(cmd overlay list --user 0 | grep -o '[A-Za-z.]*:$t[A-Za-z0-9_]*'); do cmd overlay disable --user 0 \$o >/dev/null 2>&1; done; cmd overlay list --user 0 | grep '$t' | grep -c '\\[x\\]'"
        val out = Privilege.run(script)
        val left = out.text.lines().lastOrNull()?.trim()?.toIntOrNull() ?: 0
        return if (left == 0) Result(true, "Removed. Restart the app to see the original colors.") else Result(false, "$left overlays are still enabled")
    }

    /** Colors the user set by hand, per app: resource name to color, kept as "ov_<pkg>" in preferences. */
    fun overrides(sp: android.content.SharedPreferences, pkg: String): Map<String, Int> =
        sp.getString("ov_$pkg", "").orEmpty().split(';').mapNotNull { p ->
            val i = p.lastIndexOf('='); if (i <= 0) null else p.substring(i + 1).toLongOrNull(16)?.let { p.substring(0, i) to (it.toInt() or -0x1000000) }
        }.toMap()

    fun saveOverride(sp: android.content.SharedPreferences, pkg: String, name: String, color: Int?) {
        val m = overrides(sp, pkg).toMutableMap()
        if (color == null) m.remove(name) else m[name] = color
        sp.edit().putString("ov_$pkg", m.entries.joinToString(";") { "${it.key}=%06x".format(it.value and 0xFFFFFF) }).apply()
    }

    /** When an app with hand-set colors comes to the front and its overlays are gone (reboot, or Android dropped them), they are made again. */
    fun reapply(sp: android.content.SharedPreferences, pkg: String) {
        val o = overrides(sp, pkg)
        if (o.isEmpty() || activeCount(pkg) >= o.size) return
        apply(pkg, o.toList())
    }

    /** How many BlackOut overlays of [pkg] are enabled right now. */
    fun activeCount(pkg: String): Int {
        val out = Privilege.run("cmd overlay list --user 0 | grep '${tag(pkg)}' | grep -c '\\[x\\]'")
        return out.text.lines().lastOrNull()?.trim()?.toIntOrNull() ?: 0
    }

    /** What the phone says about overlays: version, who we run as, the cmd usage. Shown in Settings > Diagnostics. */
    fun diagnose(): String = Privilege.run(
        "echo \"Android \$(getprop ro.build.version.release) (SDK \$(getprop ro.build.version.sdk)), \$(getprop ro.product.manufacturer) \$(getprop ro.product.model)\"; id; " +
        "echo; echo \"force dark property: \$(getprop debug.hwui.force_dark)\"; " +
        // read-only look at the screen color transform (what Dark pages and the black level hand to SurfaceFlinger) and the system inversion switches
        "echo \"SurfaceFlinger: \$(service check SurfaceFlinger 2>&1 | head -1)\"; dumpsys SurfaceFlinger 2>/dev/null | grep -i -m3 -E 'color.*transform|colorMatrix'; " +
        "echo \"inversion: \$(settings get secure accessibility_display_inversion_enabled) force invert: \$(settings get secure accessibility_force_invert_color_enabled)\"; " +
        "echo; cmd overlay 2>&1 | grep -i -A3 'fabricate' | head -12; echo; cmd overlay list --user 0 2>&1 | grep -c 'bo_'"
    ).text

    fun forceStop(pkg: String) { Privilege.run("am force-stop $pkg") }
}
