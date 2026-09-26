#!/usr/bin/env bash
# Wakes and unlocks the test phone. The PIN is read from $FXD_PHONE_PIN (never stored in the repo).
set -euo pipefail
adb shell input keyevent KEYCODE_WAKEUP
sleep 1
if adb shell dumpsys trust | grep -q "deviceLocked=1"; then
  adb shell input swipe 540 1800 540 600 300
  sleep 1.5
  [ -n "${FXD_PHONE_PIN:-}" ] && { adb shell input text "$FXD_PHONE_PIN"; adb shell input keyevent KEYCODE_ENTER; }
  sleep 2
fi
adb shell dumpsys trust | grep -oE "deviceLocked=[01]" | head -1
