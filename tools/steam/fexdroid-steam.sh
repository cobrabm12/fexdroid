#!/bin/sh
# Starts the Steam Linux client under FEX inside the fexdroid app (arm64 side).
# Called by the app with DISPLAY, PULSE_SERVER, HOME, FEX_ROOTFS... already set.
#
#   fexdroid-steam.sh [steam args...]
#
# - Steam lives in $HOME/.local/share/Steam (outside the rootfs, survives updates).
#   If missing, the official client is downloaded from Valve (user-initiated install).
# - The game library is $FXD_FILES/steamlib (steamapps is a symlink to it).
# - Containers are replaced by a pass-through entry point (_v2-entry-point, see there).
set -eu
FXD_FILES="${FXD_FILES:?}"
SHIM_DIR="$FXD_ROOT/usr/lib/fexdroid/steam"
STEAMROOT="$HOME/.local/share/Steam"
LIB="$FXD_FILES/steamlib"
FEX="$FXD_ROOT/usr/bin/FEX"
log() { echo "[fexdroid-steam] $*"; }

mkdir -p "$STEAMROOT" "$LIB/steamapps/common" "$HOME/.steam"

# 1) Client files.
if [ ! -x "$STEAMROOT/steam.sh" ]; then
    log "Steam client not installed; downloading the official bootstrap from Valve"
    tmp="$STEAMROOT/.bootstrap-download"
    mkdir -p "$tmp"
    "$FEX" "$FEX_ROOTFS/usr/bin/curl" -fL --retry 3 -o "$tmp/steam.tar.gz" \
        https://repo.steampowered.com/steam/archive/stable/steam_latest.tar.gz
    tar -C "$tmp" -xzf "$tmp/steam.tar.gz"
    boot=$(find "$tmp" -name 'bootstraplinux_ubuntu12_32.tar.xz' | head -n1)
    [ -n "$boot" ] || { log "bootstrap archive not found in download"; exit 1; }
    # The x86 tar execs xz through PATH: give it the guest prefixes (FEX maps them into
    # the x86 rootfs), not the arm64 PATH this script runs with up to here.
    PATH=/usr/bin:/bin "$FEX" "$FEX_ROOTFS/usr/bin/tar" -C "$STEAMROOT" -xJf "$boot"
    rm -rf "$tmp"
fi

# 2) Library: Steam's steamapps -> our persistent library folder.
if [ ! -L "$STEAMROOT/steamapps" ]; then
    if [ -d "$STEAMROOT/steamapps" ]; then
        cp -a "$STEAMROOT/steamapps/." "$LIB/steamapps/" 2>/dev/null || true
        rm -rf "$STEAMROOT/steamapps"
    fi
    ln -s "$LIB/steamapps" "$STEAMROOT/steamapps"
fi
ln -sfn "$STEAMROOT" "$HOME/.steam/root"
ln -sfn "$STEAMROOT" "$HOME/.steam/steam"

# 2b) Shader pre-caching off, once: Steam downloads 2 GB of precompiled shaders per game at
# every start and "processes" them before a game starts, which makes no progress under FEX
# (NOTES N-031, N-038). Steam > Settings > Downloads turns it back on; the marker keeps this
# from overriding that choice.
cfg="$STEAMROOT/config/config.vdf"
if [ ! -e "$FXD_FILES/.shader-precache-default" ]; then
    if [ ! -e "$cfg" ]; then
        mkdir -p "$STEAMROOT/config"
        printf '"InstallConfigStore"\n{\n\t"Software"\n\t{\n\t\t"Valve"\n\t\t{\n\t\t\t"Steam"\n\t\t\t{\n\t\t\t\t"ShaderCacheManager"\n\t\t\t\t{\n\t\t\t\t\t"DisableShaderCache"\t\t"1"\n\t\t\t\t}\n\t\t\t}\n\t\t}\n\t}\n}\n' > "$cfg"
        log "shader pre-caching: off (new configuration)"
    elif grep -q '"ShaderCacheManager"' "$cfg" && ! grep -q '"DisableShaderCache"' "$cfg"; then
        sed -i '/"ShaderCacheManager"/{n;s/{/{\n\t\t\t\t\t"DisableShaderCache"\t\t"1"/;}' "$cfg"
        log "shader pre-caching: off"
    fi
    : > "$FXD_FILES/.shader-precache-default"
fi

# 3) No containers: games' runtimes get the pass-through entry point too. Steam installs
# and updates these runtimes while it runs, so keep checking in the background.
replace_entry_points() {
    for rt in "$LIB"/steamapps/common/SteamLinuxRuntime*; do
        [ -d "$rt" ] || continue
        ep="$rt/_v2-entry-point"
        if [ -f "$ep" ] && ! cmp -s "$ep" "$SHIM_DIR/_v2-entry-point"; then
            [ -f "$ep.valve" ] || mv "$ep" "$ep.valve"
            cp "$SHIM_DIR/_v2-entry-point" "$ep"
            chmod 755 "$ep"
            log "container entry point replaced in $(basename "$rt")"
        fi
    done
}
replace_entry_points
( while sleep 5; do replace_entry_points; done ) &

# 3b) Paths Steam creates under /tmp with absolute names. FEX overlays the x86
# RootFS only for paths that already exist there, so pre-create them (Steam aborts
# if it cannot create /tmp/dumps).
for d in dumps dumps01 dumps02 dumps03; do mkdir -p "$FEX_ROOTFS/tmp/$d"; done
# Steam leaves one such socket behind per start; nothing else runs at this point.
rm -f "$FEX_ROOTFS"/tmp/steam_chrome_shmem_uid* 2>/dev/null || true
chmod 1777 "$FEX_ROOTFS/tmp" 2>/dev/null || true

# 4) Environment for the Steam client.
export STEAM_RUNTIME_STEAMRT="$SHIM_DIR"   # steamwebhelper: no pressure-vessel
export STEAMOS=1                           # skip host library checks (FEX wiki)
export STEAM_RUNTIME=1
export PRESSURE_VESSEL_BATCH=1
export TMPDIR="${TMPDIR:-$FXD_ROOT/tmp}"
export SDL_VIDEODRIVER=x11

# PATH for the x86 guest: standard prefixes only. FEX overlays them onto the x86
# RootFS; the arm64 rootfs paths from the app environment would make `env`, `bash`
# etc. resolve to native binaries, which FEX then runs natively (and their x86
# children fail with "Exec format error").
# Windows larger than the virtual screen would be cut off (tools/fxwmfit); arm64, ends with X.
[ -x "$FXD_ROOT/usr/bin/fxwmfit" ] && LD_PRELOAD="$FXD_ROOT/usr/lib/fexdroid/libfxpath.so" "$FXD_ROOT/usr/bin/fxwmfit" 2>/dev/null &
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
# steam.sh moves its output (and Steam's) to logs/console-linux.txt as soon as its runtime is
# set up, so the app's log ended at "Steam runtime environment up-to-date!" and said nothing
# about a Steam that exits right after. Keep it on stdout: the app shows and sends its log.
export STEAM_RUNTIME_LOGGER=0
log "starting Steam (FEX) with root $STEAMROOT"
cd "$STEAMROOT"
# Debugging: files/strace-steam.txt holds strace options (e.g. "-e trace=bind,connect");
# the trace of every process goes to files/steam.strace.
set -- -no-cef-sandbox -cef-disable-gpu -cef-disable-gpu-compositing "$@"
# -noverifyfiles skips the slow file check under FEX, but on a fresh bootstrap it also skips
# the download of the client itself ("Verification skipped", then steamui.so is missing).
if [ -f "$STEAMROOT/ubuntu12_32/steamui.so" ]; then
  set -- -noverifyfiles "$@"
else
  log "first start: Steam downloads its client now (about 1.5 GB, slow under FEX)"
fi
# Extra Steam options, one line (e.g. "-cef-enable-debugging"), like dota-launch-options.txt.
if [ -f "$FXD_FILES/steam-launch-options.txt" ]; then
  # shellcheck disable=SC2046
  set -- "$@" $(cat "$FXD_FILES/steam-launch-options.txt")
fi
if [ -f "$FXD_FILES/strace-steam.txt" ]; then
  # shellcheck disable=SC2046
  exec "$FXD_ROOT/usr/bin/strace" -f -o "$FXD_FILES/steam.strace" $(cat "$FXD_FILES/strace-steam.txt") \
    "$FEX" "$STEAMROOT/steam.sh" "$@"
fi
exec "$FEX" "$STEAMROOT/steam.sh" "$@"
