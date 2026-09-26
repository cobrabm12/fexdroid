#!/usr/bin/env bash
# Copies an installed Steam game from this PC into the phone app's Steam library
# (files/steamlib/steamapps/...), in resumable ~4 GB batches over `adb exec-in`.
# The user's own installed copy; nothing is bundled into the APK.
#
# Usage: adb-copy-steam-game.sh <appid> [steam-root]   e.g. 570 (Dota 2)
set -euo pipefail
APPID="$1"
STEAM="${2:-$HOME/.local/share/Steam}"
PKG="${FXD_PACKAGE:-ro.cobrabm.fexdroid}"
cd "$(dirname "$0")/.."
MANIFEST="$STEAM/steamapps/appmanifest_$APPID.acf"
[[ -f "$MANIFEST" ]] || { echo "no $MANIFEST"; exit 1; }
DIR=$(sed -n 's/.*"installdir"[[:space:]]*"\(.*\)".*/\1/p' "$MANIFEST")
STATE="build/copy-state/$APPID"
mkdir -p "$STATE"

# Batch file lists (paths relative to steamapps/common), excluding Windows binaries and replays.
if [[ ! -f "$STATE/batches.done-listing" ]]; then
  ( cd "$STEAM/steamapps/common" && find "$DIR" -type f ! -path "*/win64/*" ! -path "*/replays/*" -printf '%s\t%p\n' ) |
    python3 -c '
import sys
limit, cur, n, out = 4 << 30, 0, 0, None
for line in sys.stdin:
    size, path = line.rstrip("\n").split("\t", 1)
    if out is None or cur >= limit:
        n += 1; cur = 0
        out = open(f"'"$STATE"'/batch-{n:03d}.list", "w")
    out.write(path + "\n"); cur += int(size)
'
  touch "$STATE/batches.done-listing"
fi

for list in "$STATE"/batch-*.list; do
  [[ -f "$list.ok" ]] && continue
  for attempt in 1 2 3; do
    echo "$(date +%T) $(basename "$list") (attempt $attempt): $(wc -l < "$list") files"
    if tar -C "$STEAM/steamapps/common" --hard-dereference -cf - -T "$list" |
       adb exec-in run-as "$PKG" sh -c 'mkdir -p files/steamlib/steamapps/common && cd files/steamlib/steamapps/common && tar -xf -'; then
      touch "$list.ok"; break
    fi
    sleep 5; adb wait-for-device
  done
  [[ -f "$list.ok" ]] || { echo "batch failed 3 times: $list"; exit 1; }
done
# Steam recognizes the install through its app manifest.
adb exec-in run-as "$PKG" sh -c "cat > files/steamlib/steamapps/appmanifest_$APPID.acf" < "$MANIFEST"
echo "done: $DIR"
