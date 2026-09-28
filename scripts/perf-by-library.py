#!/usr/bin/env python3
"""usage: perf-by-library.py <perf.data> <perf-PID.map>

Shares of a simpleperf profile by guest library, with FEX's perf map (FEX_LIBRARYJITNAMING=1)."""
import bisect, collections, os, re, subprocess, sys
data, pmap = sys.argv[1], sys.argv[2]
SP = os.path.expanduser("~/Android/Sdk/ndk/29.0.14206865/simpleperf/bin/linux/x86_64/simpleperf")
regions = []
for l in open(pmap, errors="replace"):
    f = l.rstrip("\n").split(" ", 2)
    if len(f) == 3:
        regions.append((int(f[0], 16), int(f[1], 16), os.path.basename(f[2])))
regions.sort()
starts = [r[0] for r in regions]
def guest(addr):
    i = bisect.bisect_right(starts, addr) - 1
    if i >= 0 and addr < regions[i][0] + regions[i][1]:
        return regions[i][2]
    return None
out = subprocess.run([SP, "report-sample", "-i", data], capture_output=True, text=True).stdout
count = collections.Counter(); total = 0
addr = None
for l in out.splitlines():
    l = l.strip()
    if l.startswith("sample:"):
        total += 1; addr = None; want = True
    elif l.startswith("vaddr_in_file:") and want:
        addr = int(l.split(":")[1], 16)
    elif l.startswith("file:") and want:
        f = l.split(":", 1)[1].strip()
        if f in ("unknown", "[unknown]") or "anon" in f:
            count["JIT: " + (guest(addr) or "(not in the map)")] += 1
        else:
            count["host: " + os.path.basename(f)] += 1
        want = False
print(total, "samples")
for k, v in count.most_common(28):
    print("%6.2f%%  %s" % (v * 100 / total, k))
