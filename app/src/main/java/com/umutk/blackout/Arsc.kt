package com.umutk.blackout

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** One color resource entry of an app's resources.arsc (a literal color or an @color reference), for one config. */
class ColorEntry(val id: Int, val name: String, val night: Boolean, val type: Int, val data: Long)

/** A color resource that ends up as a dark neutral grey: what BlackOut can turn into black. */
class Candidate(val id: Int, val name: String, val color: Int, val night: Boolean)

class ScanResult(val pkg: String, val entries: Int, val references: Int, val nightEntries: Int, val namesStripped: Boolean, val candidates: List<Candidate>)

/**
 * Minimal reader of the binary resource table (resources.arsc) - only what is needed to list the `color` resources,
 * follow @color references and find the dark grey surfaces. tools/arsc_scan.py is the reference implementation.
 */
object Arsc {
    private const val TYPE_TABLE = 0x0002
    private const val TYPE_PACKAGE = 0x0200
    private const val TYPE_TYPE = 0x0201

    private class Buf(val b: ByteBuffer) {
        fun u8(o: Int) = b.get(o).toInt() and 0xff
        fun u16(o: Int) = b.getShort(o).toInt() and 0xffff
        fun u32(o: Int) = b.getInt(o).toLong() and 0xffffffffL
        fun i32(o: Int) = b.getInt(o)
    }

    private fun stringPool(b: Buf, off: Int): List<String> {
        val count = b.u32(off + 8).toInt()
        val utf8 = (b.u32(off + 16) and 0x100L) != 0L
        val start = b.u32(off + 20).toInt()
        val hdr = b.u16(off + 2)
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            var p = off + start + b.u32(off + hdr + 4 * i).toInt()
            if (utf8) {
                val n = b.u8(p); p += 1; if (n and 0x80 != 0) p += 1
                var l = b.u8(p); p += 1
                if (l and 0x80 != 0) { l = ((l and 0x7f) shl 8) or b.u8(p); p += 1 }
                val bytes = ByteArray(l) { b.b.get(p + it) }
                out.add(String(bytes, Charsets.UTF_8))
            } else {
                var n = b.u16(p); p += 2
                if (n and 0x8000 != 0) { n = ((n and 0x7fff) shl 16) or b.u16(p); p += 2 }
                val bytes = ByteArray(2 * n) { b.b.get(p + it) }
                out.add(String(bytes, Charsets.UTF_16LE))
            }
        }
        return out
    }

    fun readEntries(arsc: ByteArray): Pair<String, List<ColorEntry>> {
        val b = Buf(ByteBuffer.wrap(arsc).order(ByteOrder.LITTLE_ENDIAN))
        require(b.u16(0) == TYPE_TABLE) { "not a resource table" }
        val tableHdr = b.u16(2); val tableSize = minOf(b.u32(4), arsc.size.toLong()).toInt()
        var pos = tableHdr
        val entries = ArrayList<ColorEntry>()
        var pkgName = ""
        while (pos + 8 <= tableSize) {
            val ctype = b.u16(pos); val chdr = b.u16(pos + 2); val csize = b.u32(pos + 4).toInt()
            if (csize <= 0) break
            if (ctype == TYPE_PACKAGE) {
                val pkgId = b.u32(pos + 8).toInt()
                val nameChars = CharArray(128) { b.u16(pos + 12 + 2 * it).toChar() }
                pkgName = String(nameChars).substringBefore('\u0000')
                val types = stringPool(b, pos + b.u32(pos + 268).toInt())
                val keys = stringPool(b, pos + b.u32(pos + 276).toInt())
                var p = pos + chdr
                while (p + 8 <= pos + csize) {
                    val t = b.u16(p); val h = b.u16(p + 2); val s = b.u32(p + 4).toInt()
                    if (s <= 0) break
                    if (t == TYPE_TYPE) {
                        val tid = b.u8(p + 8); val flags = b.u8(p + 9); val count = b.u32(p + 12).toInt(); val estart = b.u32(p + 16).toInt()
                        val cfg = p + 20
                        val uiMode = if (b.u32(cfg) > 29) b.u8(cfg + 29) else 0
                        val night = (uiMode and 0x30) == 0x20
                        if (tid - 1 < types.size && types[tid - 1] == "color") {
                            val idx = ArrayList<Int>(); val off = ArrayList<Int>()
                            if (flags and 0x01 != 0) {          // sparse
                                for (i in 0 until count) { idx.add(b.u16(p + h + 4 * i)); off.add(b.u16(p + h + 4 * i + 2) * 4) }
                            } else if (flags and 0x02 != 0) {   // 16-bit offsets
                                for (i in 0 until count) { val o = b.u16(p + h + 2 * i); if (o != 0xFFFF) { idx.add(i); off.add(o * 4) } }
                            } else {
                                for (i in 0 until count) { val o = b.u32(p + h + 4 * i); if (o != 0xFFFFFFFFL) { idx.add(i); off.add(o.toInt()) } }
                            }
                            for (k in idx.indices) {
                                val e = p + estart + off[k]
                                val eflags = b.u16(e + 2); val key = b.u32(e + 4).toInt()
                                if (eflags and 0x01 != 0 || eflags and 0x08 != 0) continue // complex / compact: not a plain color
                                val vtype = b.u8(e + 8 + 3); val data = b.u32(e + 8 + 4)
                                if (vtype == 0x1c || vtype == 0x1d || vtype == 0x1e || vtype == 0x1f || vtype == 0x01) {
                                    val rid = (pkgId shl 24) or (tid shl 16) or idx[k]
                                    entries.add(ColorEntry(rid, keys.getOrElse(key) { "?" }, night, vtype, data))
                                }
                            }
                        }
                    }
                    p += s
                }
            }
            pos += csize
        }
        return pkgName to entries
    }

    private fun argb(type: Int, d: Long): Int = when (type) {
        0x1c -> d.toInt()
        0x1d -> (0xFF000000L or d).toInt()
        0x1e -> { val a = ((d shr 12) and 15).toInt(); val r = ((d shr 8) and 15).toInt(); val g = ((d shr 4) and 15).toInt(); val bl = (d and 15).toInt(); ((a * 17) shl 24) or ((r * 17) shl 16) or ((g * 17) shl 8) or (bl * 17) }
        else -> { val r = ((d shr 8) and 15).toInt(); val g = ((d shr 4) and 15).toInt(); val bl = (d and 15).toInt(); (0xFF shl 24) or ((r * 17) shl 16) or ((g * 17) shl 8) or (bl * 17) }
    }

    private fun resolve(table: Map<Long, ColorEntry>, id: Int, night: Boolean, depth: Int = 0): Int? {
        if (depth > 8) return null
        val e = table[key(id, night)] ?: table[key(id, false)] ?: return null
        return if (e.type == 0x01) resolve(table, e.data.toInt(), night, depth + 1) else argb(e.type, e.data)
    }

    private fun key(id: Int, night: Boolean) = (id.toLong() and 0xffffffffL) or (if (night) 1L shl 40 else 0L)

    fun isDarkGray(c: Int, limit: Int, spread: Int = 24): Boolean {
        val a = (c ushr 24) and 255; val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        return a == 255 && mx in 1..limit && mx - mn <= spread
    }

    /** Reads the app's base APK and returns the color resources that resolve to a dark neutral grey. */
    fun scan(apkPath: String, limit: Int = 0x40, all: Boolean = false): ScanResult {
        val bytes = ZipFile(apkPath).use { z ->
            val e = z.getEntry("resources.arsc") ?: error("no resources.arsc")
            z.getInputStream(e).readBytes()
        }
        val (pkg, entries) = readEntries(bytes)
        val table = HashMap<Long, ColorEntry>(entries.size * 2)
        for (e in entries) table[key(e.id, e.night)] = e
        val found = LinkedHashMap<Int, Candidate>()
        for (e in entries) {
            val c = resolve(table, e.id, e.night) ?: continue
            // all = every opaque color (whites and accents too), otherwise only the dark neutral greys
            if (if (all) ((c ushr 24) and 255) == 255 else isDarkGray(c, limit)) found.putIfAbsent(e.id, Candidate(e.id, e.name, c, e.night))
        }
        // names like "0_resource_name_obfuscated" cannot be told apart by an overlay
        val distinct = entries.map { it.name }.toSet().size
        val stripped = entries.size > 20 && distinct < entries.size * 0.5
        return ScanResult(pkg, entries.size, entries.count { it.type == 0x01 }, entries.count { it.night }, stripped, found.values.sortedBy { it.name })
    }
}
