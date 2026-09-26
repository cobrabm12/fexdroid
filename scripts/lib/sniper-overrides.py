#!/usr/bin/env python3
"""Applies "host" core-library overrides to a Steam Runtime sniper tree.

pressure-vessel does this inside Steam's container: when the host glibc (and
libstdc++/libgcc) is newer than the runtime's, the host copy wins, because newer
glibc runs older binaries but not the other way round. On the phone the "host" for
x86 guests is our Debian 13 x86 rootfs; FEX's guest thunk libraries (libvulkan-guest
etc.) are built against it and need glibc >= 2.38 / GLIBCXX_3.4.32.

usage: sniper-overrides.py <sniper-tree> <debian-x86-tree>
"""
import os, shutil, sys

sniper, host = sys.argv[1:3]
ARCHES = {"x86_64-linux-gnu": "ld-linux-x86-64.so.2", "i386-linux-gnu": "ld-linux.so.2"}
LIBS = ["libc.so.6", "libm.so.6", "libmvec.so.1", "libpthread.so.0", "libdl.so.2", "librt.so.1",
        "libresolv.so.2", "libanl.so.1", "libutil.so.1", "libnss_files.so.2", "libnss_dns.so.2",
        "libc_malloc_debug.so.0", "libstdc++.so.6", "libgcc_s.so.1", "libatomic.so.1"]

def host_file(triplet, name):
    for d in (f"usr/lib/{triplet}", f"lib/{triplet}"):
        p = os.path.join(host, d, name)
        if os.path.exists(p):
            return os.path.realpath(p)
    return None

def sniper_targets(triplet, name):
    """Every path in the sniper tree where the library is found (symlink or file)."""
    out = []
    for d in (f"lib/{triplet}", f"usr/lib/{triplet}"):
        p = os.path.join(sniper, d, name)
        if os.path.lexists(p) and not os.path.islink(os.path.join(sniper, d.split("/")[0])):
            out.append(p)
    return out

n = 0
for triplet, ldso in ARCHES.items():
    for name in LIBS + [ldso]:
        src = host_file(triplet, name)
        if not src:
            continue
        targets = sniper_targets(triplet, name) or [os.path.join(sniper, f"lib/{triplet}", name)]
        for t in targets:
            os.makedirs(os.path.dirname(t), exist_ok=True)
            if os.path.lexists(t):
                os.unlink(t)
            shutil.copy2(src, t)
            n += 1
# Program interpreters: /lib64/ld-linux-x86-64.so.2 and /lib/ld-linux.so.2.
for link, rel in (("lib64/ld-linux-x86-64.so.2", "../lib/x86_64-linux-gnu/ld-linux-x86-64.so.2"),
                  ("lib/ld-linux.so.2", "i386-linux-gnu/ld-linux.so.2")):
    p = os.path.join(sniper, link)
    if os.path.exists(os.path.join(os.path.dirname(p), rel)):
        if os.path.lexists(p):
            os.unlink(p)
        os.symlink(rel, p)
open(os.path.join(sniper, "usr/lib/fexdroid-overrides.txt"), "w").write(
    "core libraries replaced by Debian 13 copies (glibc 2.41), see scripts/lib/sniper-overrides.py\n")
print(f"sniper-overrides: {n} files replaced")
