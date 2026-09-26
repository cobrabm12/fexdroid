#!/usr/bin/env bash
# Copies the Steam client program files from this PC's Steam install into the phone
# app's HOME (files/home/user/.local/share/Steam), so the phone does not have to
# bootstrap/update ~2 GB through FEX. Account data is NOT copied: config/, userdata/,
# local.vdf and other top-level .vdf files (login tokens, settings) stay on the PC;
# the user logs in on the phone (QR code).
set -euo pipefail
STEAM="${1:-$HOME/.local/share/Steam}"
PKG="${FXD_PACKAGE:-ro.cobrabm.fexdroid}"
cd "$STEAM"
tar --hard-dereference -cf - \
  --exclude=./steamapps --exclude=./userdata --exclude=./config --exclude=./logs \
  --exclude=./appcache --exclude=./depotcache --exclude=./dumps --exclude=./compatibilitytools.d \
  --exclude='./*.vdf' --exclude=./shader_cache_temp_dir_vk_64 --exclude=./shader_cache_temp_dir_d3d11 \
  --exclude='./ubuntu12_32/steam-runtime.old' \
  . | adb exec-in run-as "$PKG" sh -c 'mkdir -p files/home/user/.local/share/Steam && cd files/home/user/.local/share/Steam && tar -xf -'
echo "Steam client copied"
