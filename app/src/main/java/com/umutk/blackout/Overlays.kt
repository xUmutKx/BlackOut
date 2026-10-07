package com.umutk.blackout

/**
 * Turns colour resources of other apps black with fabricated runtime resource overlays (what `cmd overlay fabricate` creates).
 * One overlay per resource, all named bo_<package>_<resource>, so they can be listed and removed again.
 */
object Overlays {
    private fun tag(pkg: String) = "bo_" + pkg.replace('.', '_') + "_"
    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_]"), "_")
    private fun oname(pkg: String, res: String) = tag(pkg) + safe(res)

    /** The colour with every channel scaled by [keep] (0 = pure black), keeping alpha. */
    fun scaled(color: Int, keep: Float): Int {
        val r = (((color shr 16) and 255) * keep).toInt(); val g = (((color shr 8) and 255) * keep).toInt(); val b = ((color and 255) * keep).toInt()
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

    /** Creates + enables overlays for [items] (resource name to new colour) of [pkg]. */
    fun apply(pkg: String, items: List<Pair<String, Int>>): Result {
        if (items.isEmpty()) return Result(true, "nothing to apply")
        val sb = StringBuilder()
        // owner of a fabricated overlay is "root" for root, "com.android.shell" for the shell user
        var first = true
        for ((res, color) in items) {
            val n = oname(pkg, res)
            // the first one keeps its error output so a failure can be shown to the user
            val sink = if (first) "2>&1 | head -c 300; " else ">/dev/null 2>&1; "
            if (first) sb.append("echo \"fabricate:\"; ")
            sb.append("cmd overlay fabricate --target $pkg --name $n $pkg:color/$res 0x1c 0x${"%08x".format(color)} $sink")
            if (first) sb.append("echo \"enable:\"; ")
            val en = "(cmd overlay enable --user 0 root:$n || cmd overlay enable --user 0 com.android.shell:$n)"
            sb.append(en).append(if (first) " 2>&1 | head -c 300; " else " >/dev/null 2>&1; ")
            first = false
        }
        sb.append("echo; echo count:; cmd overlay list --user 0 | grep '${tag(pkg)}' | grep -c '\\[x\\]'")
        val out = Privilege.run(sb.toString())
        val made = out.text.lines().lastOrNull()?.trim()?.toIntOrNull() ?: 0
        val log = out.text.substringBefore("count:").trim()
        return if (made > 0) Result(true, "$made overlays active for $pkg. Close the app from recents and open it again.")
        else Result(false, "No overlay is enabled. Android said:\n" + log.ifBlank { "(nothing: is 'cmd overlay' available on this Android version?)" })
    }

    /** Disables every BlackOut overlay of [pkg] (and tries to unregister them). */
    fun remove(pkg: String): Result {
        val t = tag(pkg)
        val script = "for o in \$(cmd overlay list --user 0 | grep -o '[A-Za-z.]*:$t[A-Za-z0-9_]*'); do cmd overlay disable --user 0 \$o >/dev/null 2>&1; done; cmd overlay list --user 0 | grep '$t' | grep -c '\\[x\\]'"
        val out = Privilege.run(script)
        val left = out.text.lines().lastOrNull()?.trim()?.toIntOrNull() ?: 0
        return if (left == 0) Result(true, "Removed. Restart the app to see the original colours.") else Result(false, "$left overlays are still enabled")
    }

    /** How many BlackOut overlays of [pkg] are enabled right now. */
    fun activeCount(pkg: String): Int {
        val out = Privilege.run("cmd overlay list --user 0 | grep '${tag(pkg)}' | grep -c '\\[x\\]'")
        return out.text.lines().lastOrNull()?.trim()?.toIntOrNull() ?: 0
    }

    /** What the phone says about overlays: version, who we run as, the cmd usage. Shown in Settings > Diagnostics. */
    fun diagnose(): String = Privilege.run(
        "echo \"Android \$(getprop ro.build.version.release) (SDK \$(getprop ro.build.version.sdk)), \$(getprop ro.product.manufacturer) \$(getprop ro.product.model)\"; id; " +
        "echo; cmd overlay 2>&1 | grep -i -A3 'fabricate' | head -12; echo; cmd overlay list --user 0 2>&1 | grep -c 'bo_'"
    ).text

    fun forceStop(pkg: String) { Privilege.run("am force-stop $pkg") }
}
