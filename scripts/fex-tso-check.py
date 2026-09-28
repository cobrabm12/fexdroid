#!/usr/bin/env python3
"""Does a guest library's translated code use TSO memory accesses? (on the phone, over adb)

Counts, in the code FEX made from each library (the largest regions of its perf map,
FEX_LIBRARYJITNAMING=1 and FEX_PERFMAP_DIR), the ARM64 instructions FEX emits for x86's memory
ordering (LDAPR, STLR, LDAR) and the plain ones. A library listed in ExtendedVolatileMetadata
has none of the first kind.

usage: fex-tso-check.py <pid> <perf-PID.map> <library> ...
"""
import sys, subprocess, struct, collections, os, base64
pid, pmap = sys.argv[1], sys.argv[2]
regions = collections.defaultdict(list)
for l in open(pmap, errors="replace"):
    f = l.rstrip("\n").split(" ", 2)
    if len(f) == 3: regions[os.path.basename(f[2])].append((int(f[0], 16), int(f[1], 16)))
def read(addr, size):
    page = addr & ~4095
    n = ((addr + size + 4095) & ~4095) - page
    out = subprocess.run(["adb", "shell", f"run-as ro.cobrabm.fexdroid sh -c 'dd if=/proc/{pid}/mem bs=4096 skip={page // 4096} count={n // 4096} 2>/dev/null | base64 -w0'"],
                         capture_output=True, text=True).stdout
    data = base64.b64decode(out)
    return data[addr - page: addr - page + size]
for lib in sys.argv[3:]:
    tot = collections.Counter(); n = 0
    # the largest regions: most instructions per read
    for addr, size in sorted(regions[lib], key=lambda r: -r[1])[:40]:
        code = read(addr, size)
        for (w,) in struct.iter_unpack("<I", code[:len(code) & ~3]):
            n += 1
            if w & 0x3FFFFC00 == 0x089FFC00 and (w >> 30) in (0, 1, 2, 3): tot["stlr"] += 1      # STLR(B/H/W/X)
            elif w & 0x3FFFFC00 == 0x08DFFC00: tot["ldar"] += 1
            elif w & 0x3FFFFC00 == 0x38BFC000: tot["ldapr"] += 1
            elif w & 0x3B200C00 == 0x38000000 and (w >> 22) & 3 == 0: tot["stur/str (unscaled)"] += 1
            elif w & 0x3B000000 == 0x39000000: tot["ldr/str (scaled)"] += 1
    print(lib, n, "instructions:", dict(tot))
