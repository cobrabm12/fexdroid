#!/bin/bash
# fex-bench-phone.sh label rounds [NAME=value ...]: a small x86-64 program (tools/fextest/bench.cpp) under the
# FEX of build/fex/install, on the phone, with the instructions and cycles it took (simpleperf from the NDK).
# The FEX inside the app is not touched: the test one lives in files/fextest. One run is about a second of
# one core, and the numbers repeat to a tenth of a percent, so a change of 1% in the code generator shows.
# Build the program first:
#   clang++ --target=x86_64-linux-gnu --sysroot=build/rootfs/sysroot-x86_64 -O2 -fuse-ld=lld \
#     -o build/fextest/bench tools/fextest/bench.cpp
# PUSH=0 skips copying FEX and the program to the phone again.
export ANDROID_SERIAL=${ANDROID_SERIAL:-1ac1406c}
cd "$(dirname "$0")/../build/fextest" || exit 1
PKG=ro.cobrabm.fexdroid; F=/data/user/0/$PKG/files; R=$F/rootfs
label=$1; rounds=$2; shift 2
if [ "${PUSH:-1}" = 1 ]; then
  for f in ../fex/install/data/data/$PKG/files/rootfs/usr/bin/FEX ../fex/install/data/data/$PKG/files/rootfs/usr/bin/FEXServer bench ${SIMPLEPERF:-$HOME/Android/Sdk/ndk/29.0.14206865/simpleperf/bin/android/arm64/simpleperf}; do adb push $f /data/local/tmp/fxt-$(basename $f) >/dev/null 2>&1; done
  adb shell "chmod 755 /data/local/tmp/fxt-simpleperf; run-as $PKG sh -c 'mkdir -p files/fextest/bin files/fextest/cfg; for n in FEX FEXServer; do cat /data/local/tmp/fxt-\$n > files/fextest/bin/\$n; chmod 755 files/fextest/bin/\$n; done; cat /data/local/tmp/fxt-bench > files/fextest/bench; chmod 755 files/fextest/bench'"
fi
envs="PATH=$F/fextest/bin:$R/usr/bin:$R/bin HOME=$R/home LANG=C.UTF-8 TMPDIR=$R/tmp XDG_RUNTIME_DIR=$R/tmp FXD_ROOT=$R FXD_FILES=$F FEX_ROOTFS=$F/x86_64 FEX_APP_CONFIG_LOCATION=$F/fextest/cfg/ FEX_APP_DATA_LOCATION=$F/fextest/cfg/ FEX_APP_CACHE_LOCATION=$F/fextest/cfg/cache/ $*"
adb shell "run-as $PKG sh -c 'cd files/fextest && env -i $envs $F/fextest/bin/FEX $F/fextest/bench $rounds 3 > out.txt 2>&1 &'" &
sleep 1
P=$(adb shell "run-as $PKG ps -A -o PID,NAME,ARGS" | awk '$2=="FEX" && /fextest\/bench/ {print $1}' | head -1)
[ -z "$P" ] && { echo "[$label] did not start: $(adb shell "run-as $PKG cat files/fextest/out.txt" | head -3)"; exit 1; }
adb shell setprop security.perf_harden 0
adb shell "/data/local/tmp/fxt-simpleperf stat --app $PKG -p $P -e instructions,cpu-cycles,branch-misses,task-clock -o /data/local/tmp/fxt-stat.txt" > simpleperf.log 2>&1
adb shell setprop security.perf_harden 1
wait
out=$(adb shell "run-as $PKG cat files/fextest/out.txt" | tr -d '\r' | tail -2 | tr '\n' ' ')
adb shell cat /data/local/tmp/fxt-stat.txt | python3 -c "
import sys,re
d={}
for l in sys.stdin:
    m=re.match(r'\s*([\d,\.]+)(?:\(ms\))?\s+([\w-]+)',l)
    if m: d[m.group(2)]=float(m.group(1).replace(',',''))
print('[$label] %.3f G instructions, %.3f G cycles, %.2f per cycle, %.1f missed branches per 1000, %.2f s of processor | output: $out' % (d.get('instructions',0)/1e9, d.get('cpu-cycles',0)/1e9, d.get('instructions',0)/max(d.get('cpu-cycles',1),1), d.get('branch-misses',0)/max(d.get('instructions',1),1)*1000, d.get('task-clock',0)/1000))"
