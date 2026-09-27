#!/bin/sh
# Starts Dota 2 directly under FEX, without the Steam client (bring-up/testing).
# Online features need Steam (fexdroid-steam.sh + login); the engine and renderer
# start without it. The game runs on the deployed Steam Linux Runtime "sniper" tree,
# like inside Steam's container (see _v2-entry-point), from $FXD_FILES/sniper-rootfs.
set -eu
LIB="$FXD_FILES/steamlib/steamapps/common"
GAME="$LIB/dota 2 beta/game"
[ -x "$GAME/dota.sh" ] || { echo "Dota 2 not found in $GAME"; exit 1; }
tree="$FXD_FILES/sniper-rootfs"
[ -f "$tree/.complete" ] || { echo "Game rootfs (sniper) not installed in $tree (scripts/build-game-rootfs.sh)"; exit 1; }
export FEX_ROOTFS="$tree"
# Without a running Steam client, steamclient.so (found through ~/.steam) can hang
# the game while it waits for Steam's IPC. A separate HOME without ~/.steam makes
# the game skip Steam entirely (offline, like a machine without Steam).
if [ "${FXD_DOTA_WITH_STEAM:-0}" != 1 ]; then
    export HOME="$FXD_FILES/home/direct"
    mkdir -p "$HOME"
fi
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-$FXD_ROOT/tmp}"
export SDL_VIDEODRIVER=x11
# PATH for the x86 guest: standard prefixes only. FEX overlays them onto the x86
# RootFS; the arm64 rootfs paths from the app environment would make `env`, `bash`
# etc. resolve to native binaries, which FEX then runs natively (and their x86
# children fail with "Exec format error").
# Windows larger than the virtual screen would be cut off (tools/fxwmfit); arm64, ends with X.
[ -x "$FXD_ROOT/usr/bin/fxwmfit" ] && LD_PRELOAD="$FXD_ROOT/usr/lib/fexdroid/libfxpath.so" "$FXD_ROOT/usr/bin/fxwmfit" 2>/dev/null &
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
echo "[fexdroid-dota] rootfs $tree"
cd "$GAME"
# Extra launch options, like Steam's "Launch Options" (one line, e.g. "+cl_showfps 2").
extra=""
[ -f "$FXD_FILES/dota-launch-options.txt" ] && extra="$(cat "$FXD_FILES/dota-launch-options.txt")"
# shellcheck disable=SC2086
exec "$FXD_ROOT/usr/bin/FEX" "$GAME/dota.sh" -novid -console -fullscreen $extra "$@"
