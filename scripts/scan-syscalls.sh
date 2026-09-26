#!/usr/bin/env bash
# Lists the raw syscall numbers an aarch64 ELF issues (mov x8,#N ... svc #0),
# and flags the ones Android's app seccomp filter traps with SIGSYS.
# Usage: scripts/scan-syscalls.sh <elf> [<elf>...]      (see NOTES.md N-017)
# Heuristic: for every "svc #0" it takes the nearest preceding "mov x8, #N"
# within 12 instructions; the generic syscall(2) wrapper (w8 from a register)
# is not counted.
set -euo pipefail
# aarch64 numbers trapped by AOSP's untrusted_app policy (SECCOMP_ALLOWLIST/BLOCKLIST).
declare -A TRAPPED=( [99]=set_robust_list [293]=rseq [439]=faccessat2
  [186]=msgget [187]=msgctl [188]=msgrcv [189]=msgsnd [190]=semget [191]=semctl
  [192]=semtimedop [193]=semop [194]=shmget [195]=shmctl [196]=shmat [197]=shmdt )
rc=0
for elf in "$@"; do
  echo "== $elf"
  nums=$(llvm-objdump -d --no-show-raw-insn "$elf" | awk '
    /mov[[:space:]]+[wx]8, #/ { n=$0; sub(/.*#/, "", n); sub(/[[:space:]].*/, "", n); last=strtonum(n); age=0; next }
    /svc[[:space:]]+#0/ { if (age <= 12 && last != "") print last; last=""; age=0; next }
    /^[[:space:]]+[0-9a-f]+:/ { age++ }' | sort -n | uniq -c)
  echo "$nums" | awk '{printf "%s:%s ", $2, $1} END {print ""}'
  while read -r cnt n; do
    [[ -z "${n:-}" ]] && continue
    [[ -n "${TRAPPED[$n]:-}" ]] && { echo "  TRAPPED syscall $n (${TRAPPED[$n]}) issued at $cnt site(s)"; rc=1; }
  done <<< "$nums"
done
[[ $rc == 0 ]] && echo "no trapped syscalls found" || echo "trapped syscalls present"
exit $rc
