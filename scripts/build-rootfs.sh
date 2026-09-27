#!/usr/bin/env bash
# Build the Debian 13 (trixie) arm64 glibc rootfs and the matching cross-compile sysroot.
#
# Outputs (in build/rootfs/):
#   rootfs-arm64.tar            runtime rootfs shipped to the phone (trimmed)
#   sysroot-arm64/              runtime + -dev packages, used as --sysroot for cross builds
#   rootfs-arm64.packages.txt   exact package versions (reproducibility record)
#
# Set SNAPSHOT=YYYYMMDDTHHMMSSZ to pin the Debian archive via snapshot.debian.org.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=build/rootfs
IMAGE="debian:trixie-slim"
mkdir -p "$OUT"
scripts/ensure-binfmt.sh

APT_SNAPSHOT=""
[[ -n "${SNAPSHOT:-}" ]] && APT_SNAPSHOT="--snapshot $SNAPSHOT"

# Packages present on the phone at runtime. Grows with the phases.
RUNTIME_PKGS=(
  dash coreutils busybox procps file strace          # phase 1 + debugging
  libstdc++6 libzstd1 zlib1g libexpat1               # FEX host side
  libdrm2 libx11-6 libx11-xcb1 libxcb1 libxcb-dri3-0 libxcb-present0 libxcb-shm0
  libxcb-sync1 libxcb-xfixes0 libxcb-randr0 libxshmfence1 libwayland-client0
  libvulkan1 vulkan-tools                            # phase 3: vulkaninfo, vkcube
  xvfb                                               # phase 3: X server (PLAN.md D3)
  x11-utils                                          # phase 4: xev/xdpyinfo to verify input
  pulseaudio pulseaudio-utils                        # phase 4: audio server (pipe sink -> AAudio)
  mesa-vulkan-drivers                                # lavapipe: CPU Vulkan for GPUs without Turnip (Mali, ...)
  libgl1-mesa-dri                                    # swrast: Xvfb's GLX extension (Steam's vgui needs a GLX visual)
)
# Extra packages only needed to cross-compile FEX/Mesa against this sysroot.
DEV_PKGS=(
  libc6-dev libstdc++-14-dev libzstd-dev zlib1g-dev libexpat1-dev libdrm-dev
  libx11-dev libx11-xcb-dev libxcb1-dev libxcb-dri3-dev libxcb-present-dev libxcb-shm0-dev
  libxcb-sync-dev libxcb-xfixes0-dev libxcb-randr0-dev libxshmfence-dev libxrandr-dev
  libwayland-dev libvulkan-dev libudev-dev
)

build_tree() { # $1 = name, $2 = docker platform, rest = packages
  local name=$1 platform=$2; shift 2
  local cname="fexdroid-$name-$$"
  docker rm -f "$cname" >/dev/null 2>&1 || true
  docker run --platform "$platform" --name "$cname" "$IMAGE" bash -euc "
    export DEBIAN_FRONTEND=noninteractive
    apt-get update $APT_SNAPSHOT -qq
    apt-get install -y -qq --no-install-recommends $* >/dev/null
    apt-get clean
    rm -rf /var/lib/apt/lists/*
    dpkg-query -W -f='\${Package} \${Version}\n' > /packages.txt
  "
  docker export "$cname" > "$OUT/$name.tar"
  docker rm "$cname" >/dev/null
}

if [[ "${ONLY_X86:-0}" == 1 ]]; then SKIP_ARM=1; fi
if [[ "${SKIP_ARM:-0}" != 1 ]]; then
echo ">> runtime rootfs"
build_tree rootfs-arm64-raw linux/arm64 "${RUNTIME_PKGS[@]}"
echo ">> sysroot"
build_tree sysroot-arm64-raw linux/arm64 "${RUNTIME_PKGS[@]}" "${DEV_PKGS[@]}"

# Trim the runtime rootfs: docs, man pages, locales, container leftovers.
rm -rf "$OUT/stage" && mkdir -p "$OUT/stage"
tar -C "$OUT/stage" -xf "$OUT/rootfs-arm64-raw.tar" 2>/dev/null || true
( cd "$OUT/stage"
  rm -rf usr/share/doc/* usr/share/man/* usr/share/info/* usr/share/lintian \
         var/cache/debconf/*-old var/log/* .dockerenv
  find usr/share/locale -mindepth 1 -maxdepth 1 ! -name 'locale.alias' -exec rm -rf {} + 2>/dev/null || true
  # Vulkan: Turnip (our build, scripts/build-mesa.sh) on Adreno; lavapipe (LLVM, from
  # Debian's mesa-vulkan-drivers) as the software fallback on phones without KGSL
  # (Mali, Xclipse, PowerVR; NOTES.md N-027). Every other Debian Vulkan driver targets
  # desktop/DRM GPUs that an Android app can never open: drop them and their manifests.
  for icd in usr/share/vulkan/icd.d/*.json; do
    case "$icd" in */lvp_icd.*) ;; *) rm -f "$icd" ;; esac
  done
  find usr/lib/aarch64-linux-gnu -maxdepth 1 -name 'libvulkan_*.so' ! -name 'libvulkan_lvp.so' -delete
  # Mesa's layers: device_select is implicit (loaded into every Vulkan app, Turnip included)
  # and only reorders GPUs; the overlay is a desktop HUD. Neither is wanted here.
  rm -f usr/share/vulkan/implicit_layer.d/VkLayer_MESA_device_select.json \
        usr/share/vulkan/explicit_layer.d/VkLayer_MESA_overlay.json \
        usr/lib/aarch64-linux-gnu/libVkLayer_MESA_*.so usr/bin/mesa-overlay-control.py
  # GL drivers: Xvfb only needs swrast to bring up its GLX extension (software GLX,
  # clients render with their own Mesa through drisw). Steam's vgui asserts without a
  # GLX visual (NOTES.md N-028). Every hardware DRI driver goes: no DRM devices here.
  find usr/lib/aarch64-linux-gnu/dri -mindepth 1 ! -name swrast_dri.so ! -name libdril_dri.so -delete 2>/dev/null || true
)
mv "$OUT/stage/packages.txt" "$OUT/rootfs-arm64.packages.txt"
tar -C "$OUT/stage" --numeric-owner --owner=0 --group=0 -cf "$OUT/rootfs-arm64.tar" .
rm -rf "$OUT/stage" "$OUT/rootfs-arm64-raw.tar"

rm -rf "$OUT/sysroot-arm64" && mkdir -p "$OUT/sysroot-arm64"
tar -C "$OUT/sysroot-arm64" -xf "$OUT/sysroot-arm64-raw.tar" 2>/dev/null || true
rm -f "$OUT/sysroot-arm64-raw.tar"
# Make absolute symlinks inside the sysroot relative so the cross toolchain can follow them.
find "$OUT/sysroot-arm64" -type l -lname '/*' | while read -r l; do
  t=$(readlink "$l"); rel=$(realpath -m --relative-to="$(dirname "$l")" "$OUT/sysroot-arm64$t")
  ln -sfn "$rel" "$l"
done

fi # SKIP_ARM

# x86_64 guest rootfs for FEX (phase 2). Grows with phase 3 (vulkan-tools) and 5 (i386 for Steam).
X86_PKGS=(libc6 libstdc++6 coreutils libx11-6 libxcb1 libvulkan1 vulkan-tools  # vulkan-tools: vkcube for phase 3
          libpulse0 pulseaudio-utils)                                          # phase 4: x86 audio clients
echo ">> x86_64 guest rootfs"
build_tree rootfs-x86_64-raw linux/amd64 "${X86_PKGS[@]}"
rm -rf "$OUT/stage" && mkdir -p "$OUT/stage"
tar -C "$OUT/stage" -xf "$OUT/rootfs-x86_64-raw.tar" 2>/dev/null || true
( cd "$OUT/stage"
  rm -rf usr/share/doc/* usr/share/man/* usr/share/info/* usr/share/lintian var/log/* .dockerenv
  find usr/share/locale -mindepth 1 -maxdepth 1 ! -name 'locale.alias' -exec rm -rf {} + 2>/dev/null || true
)
mv "$OUT/stage/packages.txt" "$OUT/rootfs-x86_64.packages.txt"
tar -C "$OUT/stage" --numeric-owner --owner=0 --group=0 -cf "$OUT/rootfs-x86_64.tar" .
rm -rf "$OUT/stage" "$OUT/rootfs-x86_64-raw.tar"

du -sh "$OUT/rootfs-arm64.tar" "$OUT/sysroot-arm64" "$OUT/rootfs-x86_64.tar"
echo "glibc: $(grep '^libc6 ' "$OUT/rootfs-arm64.packages.txt")"
