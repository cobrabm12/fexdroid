#!/usr/bin/env python3
"""Where a game's threads spend their time under FEX, on the phone.

Reads, twice, /proc/<pid>/task/*/stat and FEX's statistics (FEX_PROFILESTATS=1 in
files/session-env.txt, file rootfs/dev/shm/fex-<pid>-stats, FEXCore/Utils/SHMStats.h) of the
busiest process whose command line matches, and prints what changed in between.

usage: scripts/fex-thread-stats.py [pattern=dota2] [seconds=10]      (adb: ANDROID_SERIAL)
"""
import base64, struct, subprocess, sys, time

PKG = "ro.cobrabm.fexdroid"
pattern = sys.argv[1] if len(sys.argv) > 1 else "dota2"
seconds = float(sys.argv[2]) if len(sys.argv) > 2 else 10

def sh(cmd):
    return subprocess.run(["adb", "shell", f"run-as {PKG} sh -c '{cmd}'"], capture_output=True, text=True).stdout

def pid():
    best = (0, None)
    for line in sh("ps -A -o PID,RSS,ARGS").splitlines()[1:]:
        f = line.split(None, 2)
        if len(f) == 3 and pattern in f[2] and "ps -A" not in f[2] and int(f[1]) > best[0]:
            best = (int(f[1]), int(f[0]))
    return best[1]

def sample(p):
    out = sh(f"cat /proc/{p}/task/*/stat 2>/dev/null; echo ====; "
             f"base64 -w0 files/rootfs/dev/shm/fex-{p}-stats 2>/dev/null; echo; echo ====; "
             "cat /proc/stat | head -9; echo ====; cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_max_freq")
    tasks, shm, _, freq = out.split("====\n") if out.count("====\n") == 3 else (out, "", "", "")
    threads = {}
    for line in tasks.splitlines():
        close = line.rfind(")")
        if close < 0:
            continue
        tid, name = int(line[:line.index(" ")]), line[line.index("(") + 1:close]
        f = line[close + 2:].split()
        threads[tid] = dict(name=name, utime=int(f[11]), stime=int(f[12]), cpu=int(f[36]))
    stats = {}
    raw = base64.b64decode(shm.strip() or b"")
    if len(raw) >= 64:
        version, _app, size = struct.unpack_from("<BBH", raw, 0)
        head, = struct.unpack_from("<I", raw, 52)
        off = head
        names = ("jit_time", "signal_time", "sigbus", "smc", "float_fallback", "cache_miss", "cache_rd_lock",
                 "cache_wr_lock", "jit_count", "disk_hit", "disk_miss", "disk_lookup")
        seen = set()
        while off and off + size <= len(raw) and off not in seen:
            seen.add(off)
            nxt, tid = struct.unpack_from("<II", raw, off)
            stats[tid] = dict(zip(names, struct.unpack_from("<12Q", raw, off + 8)))
            off = nxt
    return threads, stats, freq.split()

p = pid()
if not p:
    sys.exit(f"no process matching {pattern!r}")
t0 = time.time(); a = sample(p); time.sleep(seconds); b = sample(p); dt = time.time() - t0 - 0
dt = seconds + 0.0  # The two samples take the same time to collect.
HZ, CNT = 100, 19_200_000  # USER_HZ; the counter FEX reads (CNTVCT_EL0) on Snapdragon.
print(f"pid {p}, {seconds:.0f} s, frequency limits {' '.join(str(int(x) // 1000) for x in b[2])} MHz")
rows = []
for tid, t in b[0].items():
    o = a[0].get(tid)
    if not o:
        continue
    u, s = (t["utime"] - o["utime"]) / HZ / dt * 100, (t["stime"] - o["stime"]) / HZ / dt * 100
    if u + s < 1:
        continue
    st, so = b[1].get(tid), a[1].get(tid)
    d = {k: st[k] - so[k] for k in st} if st and so else None
    rows.append((u + s, tid, t, u, s, d))
print(f"{'thread':<18}{'tid':>7} {'cpu':>3} {'user%':>6} {'sys%':>6} {'jit%':>6} {'sig%':>6} "
      f"{'blocks/s':>9} {'miss/s':>8} {'sigbus/s':>9} {'smc/s':>7} {'x87/s':>7}")
for total, tid, t, u, s, d in sorted(rows, key=lambda r: -r[0])[:16]:
    line = f"{t['name']:<18}{tid:>7} {t['cpu']:>3} {u:>6.1f} {s:>6.1f}"
    if d:
        line += (f" {d['jit_time'] / CNT / dt * 100:>6.1f} {d['signal_time'] / CNT / dt * 100:>6.1f}"
                 f" {d['jit_count'] / dt:>9.0f} {d['cache_miss'] / dt:>8.0f} {d['sigbus'] / dt:>9.0f}"
                 f" {d['smc'] / dt:>7.0f} {d['float_fallback'] / dt:>7.0f}")
    print(line)
