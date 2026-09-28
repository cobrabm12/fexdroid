#!/usr/bin/env python3
"""usage: byblock.py <perf.data> <perf-PID.map> [top]: shares of a profile by translated block and by library"""
import bisect, collections, os, re, subprocess, sys
data, pmap = sys.argv[1], sys.argv[2]; top = int(sys.argv[3]) if len(sys.argv) > 3 else 25
SP = os.path.expanduser("~/Android/Sdk/ndk/29.0.14206865/simpleperf/bin/linux/x86_64/simpleperf")
out = subprocess.run([SP, "report-sample", "-i", data], capture_output=True, text=True).stdout
samples = []; addr = None; want = False
for l in out.splitlines():
    l = l.strip()
    if l.startswith("sample:"): want = True; addr = None
    elif l.startswith("vaddr_in_file:") and want: addr = int(l.split(":")[1], 16)
    elif l.startswith("file:") and want:
        f = l.split(":", 1)[1].strip()
        samples.append((addr, None if (f in ("unknown", "[unknown]") or "anon" in f) else os.path.basename(f)))
        want = False
need = sorted(set(a for a, f in samples if f is None))
lo, hi = (need[0], need[-1]) if need else (0, 0)
regions = []
for l in open(pmap, errors="replace"):
    f = l.rstrip("\n").split(" ", 2)
    if len(f) != 3: continue
    try: s = int(f[0], 16); n = int(f[1], 16)
    except ValueError: continue
    if s + n < lo or s > hi: continue
    i = bisect.bisect_left(need, s)
    if i < len(need) and need[i] < s + n: regions.append((s, n, f[2]))
regions.sort(); starts = [r[0] for r in regions]
blocks = collections.Counter(); libs = collections.Counter()
for a, f in samples:
    if f is not None: libs["host: " + f] += 1; continue
    i = bisect.bisect_right(starts, a) - 1
    if i >= 0 and a < regions[i][0] + regions[i][1]:
        name = regions[i][2]; blocks[name] += 1
        m = re.search(r"([^/ ]+\.so[^ +]*|dota2)", name)
        libs["JIT: " + (m.group(1) if m else name[:30])] += 1
    else: libs["JIT: (not in the map)"] += 1
total = len(samples); print(total, "samples")
for k, v in libs.most_common(14): print("%6.2f%%  %s" % (v * 100 / total, k))
print("-- blocks")
cum = 0
for n, (k, v) in enumerate(blocks.most_common(top)):
    cum += v; print("%6.2f%%  (%5.1f%% so far)  %s" % (v * 100 / total, cum * 100 / total, k[-90:]))
