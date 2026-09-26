#!/usr/bin/env bash
# Verify the development host has everything the build scripts need.
set -u
SDK="${FEXDROID_SDK:-$HOME/Android/Sdk}"
fail=0
check() { if eval "$2" >/dev/null 2>&1; then printf 'ok      %s\n' "$1"; else printf 'MISSING %s\n' "$1"; fail=1; fi; }
for t in adb cmake ninja clang ld.lld meson patchelf unsquashfs mkfs.erofs ccache zstd docker java; do
  check "$t" "command -v $t"
done
# Mesa's meson runs under the system Python (linuxbrew python3 may shadow it in PATH).
check "python mako (system)"      "/usr/bin/python3 -c 'import mako'"
check "binfmt qemu-aarch64"       "test -e /proc/sys/fs/binfmt_misc/qemu-aarch64"
check "arm64 container runs"      "docker run --rm --platform linux/arm64 debian:trixie-slim uname -m | grep -q aarch64"
check "SDK platform android-36"   "test -d $SDK/platforms/android-36"
check "SDK build-tools 36.1.0"    "test -d $SDK/build-tools/36.1.0"
check "SDK NDK 29"                "test -d $SDK/ndk/29.0.14206865"
exit $fail
