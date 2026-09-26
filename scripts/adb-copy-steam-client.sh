#!/usr/bin/env bash
# Copies the Steam client program files from this PC's Steam install into the phone
# app's HOME (files/home/user/.local/share/Steam), so the phone does not have to
# bootstrap/update ~2 GB through FEX. Account data is NOT copied: config/, userdata/,
# local.vdf and other top-level .vdf files (login tokens, settings) stay on the PC;
# the user logs in on the phone.
#
# Incremental and verified: only files missing on the phone or with another size are
# sent, and the result is checked again (toybox tar stops at its first error, and an
# interrupted copy once left steamui/ half written: NOTES.md N-028). Run it again to resume.
set -euo pipefail
STEAM="${1:-$HOME/.local/share/Steam}"
PKG="${FXD_PACKAGE:-ro.cobrabm.fexdroid}"
DEST=files/home/user/.local/share/Steam
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
cd "$STEAM"

pc_list() { # "size<TAB>./path", same exclusions as the original full copy
  find . \( -path ./steamapps -o -path ./userdata -o -path ./config -o -path ./logs \
         -o -path ./appcache -o -path ./depotcache -o -path ./dumps -o -path ./compatibilitytools.d \
         -o -path './shader_cache_temp_dir_*' -o -path ./ubuntu12_32/steam-runtime.old \) -prune \
       -o \( -type f -o -type l \) -printf '%s\t%p\n' |
    grep -vE $'\t\\./[^/]*\\.vdf$' || true
}
phone_list() { # toybox find -exec {} + overflows ARG_MAX; xargs splits the list.
  adb shell "run-as $PKG sh -c 'cd $DEST 2>/dev/null && find . \\( -type f -o -type l \\) -print0 | xargs -0 stat -c \"%s	%n\" > ../.fxd-steam-list; echo \$? > ../.fxd-steam-list.rc'" || true
  [ "$(adb exec-out run-as "$PKG" cat "$DEST/../.fxd-steam-list.rc" | tr -d '\r')" = 0 ] || { echo "phone listing failed" >&2; exit 1; }
  adb exec-out run-as "$PKG" cat "$DEST/../.fxd-steam-list"
}
# Files the phone generates or rewrites itself: never compare or overwrite them.
LOCAL_ONLY='^\./(package/steam_client_metrics\.bin|ubuntu12_32/steam-runtime/(pinned_libs|libcurl_compat)_(32|64)/.*|steamrt64/pv-runtime/[^/]*/var/.*)$'

for pass in 1 2 3; do
  pc_list > "$WORK/pc"
  phone_list > "$WORK/phone"
  python3 - "$WORK/pc" "$WORK/phone" "$WORK/todo" "$LOCAL_ONLY" <<'EOF'
import re, sys
def load(p):
    d = {}
    for line in open(p, encoding="utf-8", errors="surrogateescape"):
        size, _, path = line.rstrip("\n").partition("\t")
        if path:
            d[path] = size
    return d
pc, phone, out, local = load(sys.argv[1]), load(sys.argv[2]), sys.argv[3], re.compile(sys.argv[4])
todo = sorted(p for p, s in pc.items() if not local.match(p) and phone.get(p) != s)
with open(out, "wb") as f:
    for p in todo:
        f.write(p.encode("utf-8", "surrogateescape") + b"\0")
print(f"{len(pc)} files on the PC, {len(todo)} to copy" + ("" if len(todo) > 5 else ": " + ", ".join(todo)))
EOF
  [ -s "$WORK/todo" ] || { echo "Steam client complete on the phone"; exit 0; }
  echo "pass $pass: copying"
  tar --hard-dereference --null -T "$WORK/todo" -cf - |
    adb exec-in run-as "$PKG" sh -c "mkdir -p $DEST && cd $DEST && tar -xf -" || echo "tar reported an error; verifying"
done
echo "Steam client still incomplete after 3 passes" >&2
exit 1
