#!/usr/bin/env bash
# Runs a shell command inside the fexdroid app sandbox (same seccomp/SELinux as the
# app) and prints its output. Usage: scripts/adb-run.sh '<command>' [timeout-seconds]
set -euo pipefail
PKG="${FXD_PACKAGE:-ro.cobrabm.fexdroid}"
CMD="$1"; T="${2:-60}"
adb logcat -c
adb shell am force-stop "$PKG"
# Quote for the device shell: single quotes around the whole command.
adb shell "am start -n $PKG/.MainActivity --es action cmd --es cmd '${CMD//\'/\'\\\'\'}'" >/dev/null
for _ in $(seq 1 $((T * 2))); do
  sleep 0.5
  adb logcat -d -s fexdroid-linux:I | grep -q '^\S.*\[exit ' && break
done
adb logcat -d -s fexdroid-linux:I | sed 's/^.*fexdroid-linux: //' | grep -v '^--------- beginning'
