#!/usr/bin/env bash
# Phase 0: collect device facts relevant to FEX/Turnip over adb.
# Usage: scripts/phone-recon.sh [adb-serial]   -> writes docs/recon/phone-<date>.txt
set -euo pipefail
cd "$(dirname "$0")/.."
ADB=(adb); [[ $# -ge 1 ]] && ADB=(adb -s "$1")
out="docs/recon/phone-$(date +%F).txt"
sh() { "${ADB[@]}" shell "$@" 2>&1 | tr -d "\r" || true; }
section() { printf '\n== %s ==\n' "$1"; }
{
  section "identity"
  for p in ro.product.model ro.product.device ro.product.manufacturer ro.build.version.release \
           ro.build.version.sdk ro.build.display.id ro.build.version.security_patch \
           ro.soc.manufacturer ro.soc.model ro.board.platform ro.hardware ro.product.cpu.abilist \
           ro.hardware.vulkan ro.hardware.egl ro.opengles.version ro.gfx.driver.0 \
           ro.product.first_api_level ro.vendor.api_level ro.build.characteristics; do
    printf '%-36s %s\n' "$p" "$(sh getprop "$p")"
  done
  section "kernel";            sh uname -a
  section "page size";         sh getconf PAGESIZE; sh 'getprop ro.boot.hardware.cpu.pagesize; getprop ro.product.cpu.pagesize.max'
  section "cpu";               sh 'cat /proc/cpuinfo | grep -E "^(processor|CPU part|CPU implementer|Features)" | sort | uniq -c'
  section "cpu freq/topology"; sh 'for c in /sys/devices/system/cpu/cpu[0-9]*; do echo "$c $(cat $c/cpufreq/cpuinfo_max_freq 2>/dev/null) cluster=$(cat $c/topology/cluster_id 2>/dev/null)"; done'
  section "memory";            sh 'head -3 /proc/meminfo'
  section "gpu nodes";         sh 'ls -l /dev/kgsl-3d0 /dev/dri 2>&1'
  section "gpu model";         sh 'cat /sys/class/kgsl/kgsl-3d0/gpu_model /sys/class/kgsl/kgsl-3d0/gpu_chipid 2>&1'
  section "vulkan (dumpsys gpu)"; sh 'dumpsys gpu 2>&1 | head -40'
  section "selinux";           sh getenforce
  section "seccomp of shell";  sh 'grep -E "Seccomp|NoNewPrivs" /proc/self/status'
  section "sysv ipc";          sh 'ls /proc/sysvipc 2>&1; cat /proc/sys/kernel/shmmax 2>&1'
  section "binder/ashmem/memfd"; sh 'ls -l /dev/binder /dev/ashmem* 2>&1'
  section "user namespaces";   sh 'cat /proc/sys/kernel/unprivileged_userns_clone /proc/sys/user/max_user_namespaces 2>&1'
  section "storage";           sh 'df -h /data 2>&1'
  section "thermal zones";     sh 'for z in /sys/class/thermal/thermal_zone*; do echo "$(cat $z/type 2>/dev/null) $(cat $z/temp 2>/dev/null)"; done | head -40'
  section "DeX / display";     sh 'dumpsys display | grep -E "mDisplayId|mBaseDisplayInfo" | head -10'
} | tee "$out"
echo; echo "Saved to $out"
