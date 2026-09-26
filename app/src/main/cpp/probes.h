// Device probes used by the recon screen. Each probe appends human-readable
// lines to a buffer; nothing here depends on JNI so it can be unit-tested on a host.
#pragma once
#include <stddef.h>

typedef struct {
    char *data;
    size_t len, cap;
} strbuf;

void sb_printf(strbuf *sb, const char *fmt, ...) __attribute__((format(printf, 2, 3)));

// Runs each "interesting" syscall in a forked child and reports allowed / errno / SIGSYS.
void probe_syscalls(strbuf *out);
// Tests W^X: exec and mmap(PROT_EXEC) from app data, anonymous JIT-style mappings, memfd.
void probe_wx(strbuf *out, const char *data_dir, const char *native_lib_dir);
// Opens /dev/kgsl-3d0 and queries device info.
void probe_kgsl(strbuf *out);
// Enumerates Vulkan physical devices through the system loader.
void probe_vulkan(strbuf *out);
// Detects the usable virtual address size the way FEX does.
void probe_va(strbuf *out);
