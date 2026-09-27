#!/usr/bin/env bash
# One-shot, resumable deployment of the phase 5 setup to the connected phone.
# Runs detached (setsid nohup) so it survives the session; log: build/deploy-phone.log
set -uo pipefail
cd "$(dirname "$0")/.."
PKG="${FXD_PACKAGE:-ro.cobrabm.fexdroid}"
step() { echo "=== $(date +%T) $*"; }
adb wait-for-device

# First: run-as below needs the app installed.
step "APK"
adb install -r app/build/outputs/apk/legacy/debug/app-legacy-debug.apk

if [ "${SKIP_DOTA:-0}" != 1 ]; then  # SKIP_DOTA=1: let Steam on the phone install the game instead
  step "Dota 2 copy (resumable)"
  until scripts/adb-copy-steam-game.sh 570; do step "copy failed, retrying in 20 s"; sleep 20; adb wait-for-device; done
fi

step "Steam x86 rootfs"
zstd -dc build/rootfs/rootfs-x86_64-steam.tar.zst | adb exec-in run-as "$PKG" sh -c \
  'rm -rf files/x86_64-steam && mkdir -p files/x86_64-steam && cd files/x86_64-steam && tar -xf -; touch .complete'

step "Game rootfs (Steam Linux Runtime sniper + newer core libraries)"
{ zstd -dc build/rootfs/rootfs-sniper.tar.zst; } | adb exec-in run-as "$PKG" sh -c \
  'rm -rf files/sniper-rootfs && mkdir -p files/sniper-rootfs && cd files/sniper-rootfs && tar -xf -'
adb exec-in run-as "$PKG" sh -c 'cd files/sniper-rootfs && tar -xf - && touch .complete' < build/rootfs/sniper-overrides.tar

step "Steam client files"
scripts/adb-copy-steam-client.sh

step "done"
adb shell "run-as $PKG sh -c 'du -sh files/steamlib files/x86_64-steam files/home/user/.local/share/Steam'"
