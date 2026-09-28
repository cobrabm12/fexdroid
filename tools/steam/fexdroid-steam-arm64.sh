#!/bin/sh
# EXPERIMENT (NOTES N-049, N-050): Valve's native arm64 Steam client, not translated by FEX.
# Called by the app with DISPLAY, PULSE_SERVER, FEX_ROOTFS... already set.
#
#   fexdroid-steam-arm64.sh [steam args...]
#
# - The client lives in its own home, $FXD_FILES/home-arm64, apart from the x86 client's.
#   If missing, its first package is downloaded from Valve's update servers and checked
#   against the hash in Valve's list; the client downloads the rest itself.
# - Its programs name /lib/ld-linux-aarch64.so.1 as their interpreter; our glibc starts
#   them through the rootfs's loader (patches/glibc/0004) and nothing of Valve's is changed.
set -eu
FXD_FILES="${FXD_FILES:?}"
export HOME="$FXD_FILES/home-arm64"
STEAMROOT="$HOME/.local/share/Steam"
FEX="$FXD_ROOT/usr/bin/FEX"
CDN=https://client-update.steamstatic.com
LIST=steam_client_steamdeck_publicbeta_linuxarm64
log() { echo "[fexdroid-steam-arm64] $*"; }

mkdir -p "$STEAMROOT/package" "$HOME/.steam"
fetch() { # url, file
    PATH=/usr/bin:/bin "$FEX" "$FEX_ROOTFS/usr/bin/curl" -fL --retry 3 -o "$2" "$1"
}

if [ ! -x "$STEAMROOT/steamrtarm64/steam" ]; then
    log "the arm64 client is not installed; asking Valve for its list of packages"
    tmp="$STEAMROOT/.first-package"
    rm -rf "$tmp" && mkdir -p "$tmp"
    fetch "$CDN/$LIST" "$tmp/list.txt"
    # The block "bins_linuxarm64_linuxarm64" of the list: its file and its sha2.
    seed=$(busybox awk '/^\t"bins_linuxarm64_linuxarm64"/{f=1} f&&/"file"/{gsub(/"/,"",$2); print $2; exit}' "$tmp/list.txt")
    sum=$(busybox awk '/^\t"bins_linuxarm64_linuxarm64"/{f=1} f&&/"sha2"/{gsub(/"/,"",$2); print $2; exit}' "$tmp/list.txt")
    [ -n "$seed" ] && [ -n "$sum" ] || { log "Valve's list has no first package"; exit 1; }
    log "downloading $seed (about 110 MB)"
    fetch "$CDN/$seed" "$tmp/seed.zip"
    got=$(sha256sum "$tmp/seed.zip" | cut -d' ' -f1)
    [ "$got" = "$sum" ] || { log "the package does not match Valve's hash ($got, not $sum)"; exit 1; }
    mkdir -p "$tmp/out"
    busybox unzip -q -o "$tmp/seed.zip" -d "$tmp/out"
    # The archive names its files with "\" between directories.
    ( cd "$tmp/out"
      busybox find . -name '*\\*' | while IFS= read -r f; do
          to=$(printf '%s' "$f" | tr '\\' '/')
          if [ -d "$f" ]; then mkdir -p "$to"; else mkdir -p "$(dirname "$to")"; mv "$f" "$to"; fi
      done
      busybox find . -depth -type d -name '*\\*' -exec rmdir {} + 2>/dev/null || true )
    chmod -R u+rwx "$tmp/out"
    cp -a "$tmp/out/." "$STEAMROOT/"
    rm -rf "$tmp"
    [ -x "$STEAMROOT/steamrtarm64/steam" ] || { log "the package has no steamrtarm64/steam"; exit 1; }
    echo steamdeck_publicbeta > "$STEAMROOT/package/beta"
    log "first package unpacked; the client downloads the rest at its first start"
fi
ln -sfn "$STEAMROOT" "$HOME/.steam/root"
ln -sfn "$STEAMROOT" "$HOME/.steam/steam"

export TMPDIR="${TMPDIR:-$FXD_ROOT/tmp}"
export SDL_VIDEODRIVER=x11
export STEAMOS=1
export LD_LIBRARY_PATH="$STEAMROOT/steamrtarm64:$STEAMROOT/linuxarm64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
# Linux paths (/tmp, /etc, /usr...) are inside the rootfs.
export LD_PRELOAD="$FXD_ROOT/usr/lib/fexdroid/libfxpath.so"
# What libraries look for at fixed places, said outright as well.
export FONTCONFIG_SYSROOT="$FXD_ROOT"
export SSL_CERT_FILE="$FXD_ROOT/etc/ssl/certs/ca-certificates.crt"
export SSL_CERT_DIR="$FXD_ROOT/etc/ssl/certs"
export XDG_DATA_DIRS="$FXD_ROOT/usr/local/share:$FXD_ROOT/usr/share"
# The client's vgui asks the X server for a GLX visual and draws through Mesa's software
# driver; ld.so opens the driver from Mesa's built-in /usr path, which it cannot find here.
export LIBGL_DRIVERS_PATH="$FXD_ROOT/usr/lib/aarch64-linux-gnu/dri"
export LIBGL_ALWAYS_SOFTWARE=1
mkdir -p "$FXD_ROOT/tmp/dumps"
[ -x "$FXD_ROOT/usr/bin/fxwmfit" ] && "$FXD_ROOT/usr/bin/fxwmfit" 2>/dev/null &

log "starting the arm64 Steam client from $STEAMROOT"
cd "$STEAMROOT"
set -- -no-cef-sandbox -cef-disable-gpu -cef-disable-gpu-compositing "$@"
if [ -f "$FXD_FILES/steam-launch-options.txt" ]; then
    # shellcheck disable=SC2046
    set -- "$@" $(cat "$FXD_FILES/steam-launch-options.txt")
fi
# The client ends with code 42 when it wants to be started again, as after its own update
# (Valve's steam.sh does the same for the x86 client).
while :; do
    rc=0
    "$STEAMROOT/steamrtarm64/steam" "$@" || rc=$?
    [ "$rc" = 42 ] || exit "$rc"
    log "the client asked to be started again"
done
