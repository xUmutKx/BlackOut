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

    private fun one(pkg: String, res: String, color: Int): String =
        "cmd overlay fabricate --target $pkg --name ${oname(pkg, res)} $pkg:color/$res 0x1c 0x${"%08x".format(color)}"

    private fun enable(pkg: String, res: String): String {
        val n = oname(pkg, res)
        return "(cmd overlay enable --user 0 root:$n || cmd overlay enable --user 0 com.android.shell:$n)"
    }

    /**
     * Creates + enables overlays for [items] (resource name to new colour) of [pkg].
     * The first one runs alone and shows Android's own answer; if it fails the rest is not even tried (an app that does not allow overlays
     * would otherwise fail hundreds of times). The rest goes in parallel batches so that a few hundred colours take seconds, not minutes.
     */
    fun apply(pkg: String, items: List<Pair<String, Int>>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result {
        if (items.isEmpty()) return Result(true, "nothing to apply")
        val (r0, c0) = items[0]
        val probe = Privilege.run("echo \"fabricate:\"; ${one(pkg, r0, c0)} 2>&1 | head -c 300; echo \"enable:\"; ${enable(pkg, r0)} 2>&1 | head -c 300; echo; echo count:; cmd overlay list --user 0 | grep '${tag(pkg)}' | grep -c '\\[x\\]'")
        val ok0 = (probe.text.lines().lastOrNull()?.trim()?.toIntOrNull() ?: 0) > 0
        if (!ok0) {
            val log = probe.text.substringBefore("count:").trim()
            val why = if (log.contains("Unable to retrieve overlay information")) "This app does not let overlays change that colour (it is not overlayable). Use Dark pages or the LSPosed module for it instead."
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
