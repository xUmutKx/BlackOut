"""Reference implementation of the resources.arsc colour scanner (the Kotlin Arsc.kt is a 1:1 port).
usage: python3 arsc_scan.py app.apk [--dark] [--all]
Prints every colour resource that RESOLVES to a dark neutral grey (following @color references), per config (day/night)."""
import struct, sys, zipfile

U16 = struct.Struct('<H'); U32 = struct.Struct('<I')
def u16(b, o): return U16.unpack_from(b, o)[0]
def u32(b, o): return U32.unpack_from(b, o)[0]

def string_pool(b, off):
    count = u32(b, off + 8); flags = u32(b, off + 16); strings_start = u32(b, off + 20); hdr = u16(b, off + 2)
    utf8 = bool(flags & 0x100)
    out = []
    for i in range(count):
        o = u32(b, off + hdr + 4 * i)
        p = off + strings_start + o
        if utf8:
            n = b[p]; p += 1
            if n & 0x80: p += 1
            l = b[p]; p += 1
            if l & 0x80: l = ((l & 0x7f) << 8) | b[p]; p += 1
            out.append(b[p:p + l].decode('utf-8', 'replace'))
        else:
            n = u16(b, p); p += 2
            if n & 0x8000: n = ((n & 0x7fff) << 16) | u16(b, p); p += 2
            out.append(b[p:p + 2 * n].decode('utf-16-le', 'replace'))
    return out

def scan(b):
    """-> (package name, package id, [ (res id, name, night, vtype, data) ]) for colour-typed (and @reference) entries of the `color` type"""
    assert u16(b, 0) == 0x0002
    table_hdr = u16(b, 2); table_size = u32(b, 4)
    pos = table_hdr; entries = []; pkg_name = ''; pkg_id = 0x7f
    while pos < min(table_size, len(b)):
        ctype = u16(b, pos); chdr = u16(b, pos + 2); csize = u32(b, pos + 4)
        if ctype == 0x0200:
            pkg_id = u32(b, pos + 8)
            pkg_name = b[pos + 12:pos + 12 + 256].decode('utf-16-le').split('\0')[0]
            type_strings = u32(b, pos + 268); key_strings = u32(b, pos + 276)
            types = string_pool(b, pos + type_strings); keys = string_pool(b, pos + key_strings)
            p = pos + chdr
            while p < pos + csize:
                t = u16(b, p); h = u16(b, p + 2); s = u32(b, p + 4)
                if t == 0x0201:
                    tid = b[p + 8]; flags = b[p + 9]; count = u32(b, p + 12); estart = u32(b, p + 16)
                    cfg = p + 20
                    ui_mode = b[cfg + 29] if u32(b, cfg) > 29 else 0
                    night = (ui_mode & 0x30) == 0x20
                    if tid - 1 < len(types) and types[tid - 1] == 'color':
                        offs = []
                        if flags & 0x01:
                            for i in range(count):
                                offs.append((u16(b, p + h + 4 * i), u16(b, p + h + 4 * i + 2) * 4))
                        elif flags & 0x02:
                            for i in range(count):
                                o = u16(b, p + h + 2 * i)
                                if o != 0xFFFF: offs.append((i, o * 4))
                        else:
                            for i in range(count):
                                o = u32(b, p + h + 4 * i)
                                if o != 0xFFFFFFFF: offs.append((i, o))
                        for idx, o in offs:
                            e = p + estart + o
                            eflags = u16(b, e + 2); key = u32(b, e + 4)
                            if eflags & 0x01 or eflags & 0x08: continue
                            vtype = b[e + 8 + 3]; data = u32(b, e + 8 + 4)
                            if vtype in (0x1c, 0x1d, 0x1e, 0x1f, 0x01):
                                rid = (pkg_id << 24) | (tid << 16) | idx
                                entries.append((rid, keys[key] if key < len(keys) else '?', night, vtype, data))
                p += s
        pos += csize
    return pkg_name, pkg_id, entries

def argb(vtype, data):
    if vtype == 0x1c: return data
    if vtype == 0x1d: return 0xFF000000 | data
    if vtype == 0x1e:
        a, r, g, bl = (data >> 12) & 15, (data >> 8) & 15, (data >> 4) & 15, data & 15
        return ((a * 17) << 24) | ((r * 17) << 16) | ((g * 17) << 8) | bl * 17
    r, g, bl = (data >> 8) & 15, (data >> 4) & 15, data & 15
    return 0xFF000000 | ((r * 17) << 16) | ((g * 17) << 8) | bl * 17

def resolve(table, rid, night, depth=0):
    """final ARGB of a colour resource for the night/day config (night falls back to day), following @color refs; None if unknown"""
    if depth > 8: return None
    e = table.get((rid, night)) or table.get((rid, False))
    if e is None: return None
    vtype, data = e
    return resolve(table, data, night, depth + 1) if vtype == 0x01 else argb(vtype, data)

def is_dark_gray(c, limit=0x40, spread=24):
    r, g, b = (c >> 16) & 255, (c >> 8) & 255, c & 255
    return (c >> 24) == 255 and 0 < max(r, g, b) <= limit and max(r, g, b) - min(r, g, b) <= spread

def candidates(entries, limit=0x40, spread=24):
    """-> [(res id, name, final colour)] : entries whose colour in SOME config is a dark neutral grey (not already pure black)"""
    table = {(rid, night): (vt, d) for rid, name, night, vt, d in entries}
    names = {rid: name for rid, name, *_ in entries}
    out = {}
    for rid, name, night, vt, d in entries:
        c = resolve(table, rid, night)
        if c is not None and is_dark_gray(c, limit, spread): out[rid] = (name, c, night)
    return [(rid, n, c, nt) for rid, (n, c, nt) in out.items()]

if __name__ == '__main__':
    z = zipfile.ZipFile(sys.argv[1]); arsc = z.read('resources.arsc')
    pkg, pid, entries = scan(arsc)
    refs = sum(1 for e in entries if e[3] == 0x01)
    print('package', pkg, '| colour entries', len(entries), '(%d references)' % refs, '| night entries', sum(1 for e in entries if e[2]))
    for rid, name, c, night in sorted(candidates(entries), key=lambda x: x[1]):
        print('0x%08x %-52s %s #%08X' % (rid, name, 'night' if night else 'day  ', c))
