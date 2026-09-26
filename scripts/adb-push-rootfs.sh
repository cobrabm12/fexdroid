#!/usr/bin/env bash
# Fast iteration: copies files into the installed app's rootfs without rebuilding
# the APK (debug builds only, via run-as). Usage: adb-push-rootfs.sh <local> <path-in-rootfs>
set -euo pipefail
PKG="${FXD_PACKAGE:-ro.cobrabm.fexdroid}"
tmp="/data/local/tmp/fxd-$(basename "$1")"
adb push "$1" "$tmp" >/dev/null
adb shell "chmod 644 $tmp; run-as $PKG sh -c 'cat $tmp > files/rootfs/$2.new && chmod \$(stat -c %a files/rootfs/$2 2>/dev/null || echo 755) files/rootfs/$2.new && mv files/rootfs/$2.new files/rootfs/$2'"
adb shell rm -f "$tmp"
echo "pushed $1 -> rootfs/$2"
