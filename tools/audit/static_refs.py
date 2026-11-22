#!/usr/bin/env python
"""Audit: static fields that hold state but are not in the mirror table's shape.

The sandbox mirrors four kinds of statics (mirror/Statics.java):
  * CLONE roots            -- AbstractDungeon.player/actionManager/currMapNode/screens/12 RNGs, ...
  * CONTAINERS             -- effectList/effectsQueue/topLevelEffects/.../DrawMaster.drawList/TipTracker.tips
  * generated mutable scalars -- every primitive/String/enum/Color static the sweep found (404 in 2008 classes)
  * shell sweep             -- ALL non-final statics of AbstractDungeon + CardCrawlGame (value save/restore)
This script prints what is left: non-final static REFERENCE fields, i.e. objects that stay shared while a
sandbox is installed. Scan the game jar and each mod jar separately.

Usage:
  python tools\audit\static_refs.py <jar> [jar ...]
"""
import io, struct, sys, zipfile


def parse_cp(d):
    pos, n = 8, struct.unpack('>H', d[8:10])[0]
    pos += 2
    cp = [None] * n
    i = 1
    while i < n:
        t = d[pos]
        pos += 1
        if t == 1:
            ln = struct.unpack('>H', d[pos:pos + 2])[0]
            pos += 2
            cp[i] = d[pos:pos + ln].decode('utf-8', 'replace')
            pos += ln
        elif t in (7, 8, 16, 19, 20):
            pos += 2
        elif t == 15:
            pos += 3
        elif t in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 4
        elif t in (5, 6):
            pos += 8
            i += 1
        else:
            raise ValueError('constant pool tag %d' % t)
        i += 1
    return cp, pos


def fields_of(b):
    cp, pos = parse_cp(b)
    pos += 6
    ifc = struct.unpack('>H', b[pos:pos + 2])[0]
    pos += 2 + 2 * ifc
    fc = struct.unpack('>H', b[pos:pos + 2])[0]
    pos += 2
    out = []
    for _ in range(fc):
        acc, ni, di, ac = struct.unpack('>HHHH', b[pos:pos + 8])
        pos += 8
        sig = None
        for _ in range(ac):
            an = struct.unpack('>H', b[pos:pos + 2])[0]
            ln = struct.unpack('>I', b[pos + 2:pos + 6])[0]
            if cp[an] == 'Signature':
                sig = b[pos + 6:pos + 6 + ln].decode('utf-8', 'replace')
            pos += 6 + ln
        out.append((acc, cp[ni], cp[di], sig))
    return out


SCALARS = ('Ljava/lang/String;', 'I', 'Z', 'F', 'J', 'D', 'B', 'C', 'S', 'V')


def main():
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    total = 0
    for jar in sys.argv[1:]:
        z = zipfile.ZipFile(jar)
        rows = []
        for name in z.namelist():
            if not name.endswith('.class') or name.startswith('META-INF'):
                continue
            try:
                fs = fields_of(z.read(name))
            except Exception:
                continue
            for acc, fn, desc, sig in fs:
                if not (acc & 0x0008) or (acc & 0x10):  # static, not final
                    continue
                if desc in SCALARS:
                    continue
                rows.append((name[:-6].replace('/', '.'), fn, desc))
        print('## %s -- %d non-final static reference field(s)' % (jar, len(rows)))
        for c, fn, desc in sorted(rows):
            print('   %s#%s : %s' % (c, fn, desc))
        total += len(rows)
    print('total: %d' % total)
    return 0


if __name__ == '__main__':
    sys.exit(main())
