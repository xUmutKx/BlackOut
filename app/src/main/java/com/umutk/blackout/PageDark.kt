package com.umutk.blackout

/**
 * Dark pages: a screen-wide colour matrix (the kind Android's own colour inversion uses) that turns white pages into the chosen
 * colour and black text into white. With [Cfg.hueSafe] only the lightness is turned around, so colours in photos and ink keep their hue.
 * Needs root or Shizuku: the matrix is handed to SurfaceFlinger. It lasts until reboot.
 */
object PageDark {
    class Cfg(val bg: Int, val text: Int, val hueSafe: Boolean)

    private fun ch(c: Int, shift: Int) = ((c shr shift) and 255) / 255f

    /** Row-major 3x3 part and the offsets, normalised 0..1: out = hi + (hi - lo) * H * in. */
    private fun parts(c: Cfg): Pair<Array<FloatArray>, FloatArray> {
        val lo = floatArrayOf(ch(c.bg, 16), ch(c.bg, 8), ch(c.bg, 0))
        val hi = floatArrayOf(ch(c.text, 16), ch(c.text, 8), ch(c.text, 0))
        if (c.hueSafe) {
            // only the lightness is turned around: white -> page colour, black -> text, while every colour keeps its hue and saturation
            val w = floatArrayOf(.299f, .587f, .114f)
            val m = Array(3) { r -> FloatArray(3) { k -> (if (r == k) 1f else 0f) + w[k] * (lo[r] - hi[r] - 1f) } }
            return m to hi
        }
        val h = arrayOf(floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, -1f, 0f), floatArrayOf(0f, 0f, -1f))
        val m = Array(3) { r -> FloatArray(3) { k -> (hi[r] - lo[r]) * h[r][k] } }
        return m to hi
    }

    /** Android ColorMatrix (4x5 row-major, offsets 0..255) for the live preview. */
    fun colorMatrix(c: Cfg): FloatArray {
        val (m, off) = parts(c)
        return floatArrayOf(
            m[0][0], m[0][1], m[0][2], 0f, off[0] * 255f,
            m[1][0], m[1][1], m[1][2], 0f, off[1] * 255f,
            m[2][0], m[2][1], m[2][2], 0f, off[2] * 255f,
            0f, 0f, 0f, 1f, 0f)
    }

    /** The same matrix, column-major 4x4, as SurfaceFlinger wants it. */
    private fun sfMatrix(c: Cfg): FloatArray {
        val (m, off) = parts(c)
        val o = FloatArray(16)
        for (col in 0..2) for (row in 0..2) o[col * 4 + row] = m[row][col]
        o[12] = off[0]; o[13] = off[1]; o[14] = off[2]; o[15] = 1f
        return o
    }

    fun apply(c: Cfg): Privilege.Out {
        val f = sfMatrix(c).joinToString(" ") { "f " + String.format(java.util.Locale.US, "%.4f", it) }
        return Privilege.run("service call SurfaceFlinger 1015 i32 1 $f")
    }

    fun fromPrefs(sp: android.content.SharedPreferences): Cfg = from({ k, d -> sp.getFloat(k, d) }, sp.getBoolean("pg_hue", true))

    /** The saved colours, read through [f] so the LSPosed module (which has no SharedPreferences) can use it too. */
    fun from(f: (String, Float) -> Float, hueSafe: Boolean): Cfg {
        val bg = (f("pg_r", 0f).toInt() shl 16) or (f("pg_g", 0f).toInt() shl 8) or f("pg_b", 0f).toInt()
        val t = f("pg_text", 235f).toInt().let { (it shl 16) or (it shl 8) or it }
        return Cfg(bg, t, hueSafe)
    }

    /** Raises the black level: every tone gets [level] (0..80 of 255) darker, so the greys up to it become pure black. The slope stays 1: contrast between tones is not stretched. */
    fun applyCrush(level: Int): Privilege.Out {
        val k = 1f; val off = -level / 255f
        val m = FloatArray(16).also { it[0] = k; it[5] = k; it[10] = k; it[12] = off; it[13] = off; it[14] = off; it[15] = 1f }
        return Privilege.run("service call SurfaceFlinger 1015 i32 1 " + m.joinToString(" ") { "f " + String.format(java.util.Locale.US, "%.4f", it) })
    }

    fun clear(): Privilege.Out = Privilege.run("service call SurfaceFlinger 1015 i32 0")
}
