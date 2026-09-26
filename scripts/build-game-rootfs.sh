#!/usr/bin/env bash
# Game rootfs: Valve's public Steam Linux Runtime 3 "sniper" platform image
# (registry.gitlab.steamos.cloud/steamrt/sniper/platform), the environment native Linux
# games like Dota 2 are built for. Inside Steam this is a pressure-vessel container that
# borrows the host's glibc and GPU drivers via /run/host; on Android there are no
# containers, so the plain platform is used as FEX_ROOTFS for games (see _v2-entry-point).
# Downloaded by the developer/user, not bundled in the APK.
#
# Output: build/rootfs/rootfs-sniper.tar.zst
set -euo pipefail
cd "$(dirname "$0")/.."
IMAGE="${SNIPER_IMAGE:-registry.gitlab.steamos.cloud/steamrt/sniper/platform:latest}"
OUT=build/rootfs
STAGE="$OUT/sniper-stage"
docker pull --platform linux/amd64 "$IMAGE" >/dev/null
cid=$(docker create --platform linux/amd64 "$IMAGE" /bin/true)
rm -rf "$STAGE" && mkdir -p "$STAGE"
docker export "$cid" | tar -C "$STAGE" -xf - 2>/dev/null || true
docker rm "$cid" >/dev/null
rm -f "$STAGE/.dockerenv"
python3 scripts/lib/relativize-symlinks.py "$STAGE"
mkdir -p "$STAGE/tmp" "$STAGE/var/tmp" "$STAGE/dev/shm" "$STAGE/run/pressure-vessel"
chmod 1777 "$STAGE/tmp" "$STAGE/var/tmp" "$STAGE/dev/shm"
grep -q 'VERSION_CODENAME=sniper' "$STAGE/usr/lib/os-release"
# Newer core libraries win, as pressure-vessel does with the host's (glibc 2.41 from the
# Debian 13 x86 rootfs): FEX's guest thunks are built against them.
[ -f build/rootfs/rootfs-x86_64-steam.tar.zst ] || { echo "run scripts/build-rootfs-steam.sh first"; exit 1; }
HOSTX="$OUT/sniper-hostlibs"; rm -rf "$HOSTX"; mkdir -p "$HOSTX"
zstd -dc build/rootfs/rootfs-x86_64-steam.tar.zst | tar -C "$HOSTX" -xf - ./usr/lib/x86_64-linux-gnu ./usr/lib/i386-linux-gnu 2>/dev/null || true
python3 scripts/lib/sniper-overrides.py "$STAGE" "$HOSTX"
rm -rf "$HOSTX"
# Dialogs go to the log instead of a GTK zenity that cannot start here.
mv "$STAGE/usr/bin/zenity" "$STAGE/usr/bin/zenity.real" 2>/dev/null || true
install -m 0755 tools/steam/zenity-log "$STAGE/usr/bin/zenity"
docker image inspect "$IMAGE" --format '{{index .RepoDigests 0}}' > "$OUT/rootfs-sniper.image.txt"
# --hard-dereference: Android apps cannot create hard links (SELinux).
tar -C "$STAGE" --numeric-owner --owner=0 --group=0 --sort=name --hard-dereference -cf - . |
  zstd -T0 -19 -q -f -o "$OUT/rootfs-sniper.tar.zst"
rm -rf "$STAGE"
du -h "$OUT/rootfs-sniper.tar.zst"; cat "$OUT/rootfs-sniper.image.txt"
