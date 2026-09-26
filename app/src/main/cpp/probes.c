// Device probes: syscall filter (seccomp), W^X policy, KGSL.
// Every risky operation runs in a forked child so a SIGSYS or crash cannot take
// the app down; the parent only observes the exit status.
#define _GNU_SOURCE
#include "probes.h"

#include <errno.h>
#include <fcntl.h>
#include <sched.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

// ---- strbuf -----------------------------------------------------------------

void sb_printf(strbuf *sb, const char *fmt, ...) {
    va_list ap;
    for (;;) {
        size_t room = sb->cap - sb->len;
        va_start(ap, fmt);
        int n = vsnprintf(sb->data ? sb->data + sb->len : NULL, room, fmt, ap);
        va_end(ap);
        if (n < 0) return;
        if ((size_t)n < room) { sb->len += (size_t)n; return; }
        size_t ncap = (sb->cap + (size_t)n + 1) * 2;
        char *p = realloc(sb->data, ncap);
        if (!p) return;
        sb->data = p;
        sb->cap = ncap;
    }
}

// ---- syscall numbers ------------------------------------------------------------
// Fallbacks for headers that predate a syscall. Numbers are the asm-generic
// table used by arm64; x86_64 hosts get them from their own headers.
#if defined(__aarch64__)
#  ifndef __NR_rseq
#    define __NR_rseq 293
#  endif
#  ifndef __NR_pidfd_open
#    define __NR_pidfd_open 434
#  endif
#  ifndef __NR_clone3
#    define __NR_clone3 435
#  endif
#  ifndef __NR_close_range
#    define __NR_close_range 436
#  endif
#  ifndef __NR_openat2
#    define __NR_openat2 437
#  endif
#  ifndef __NR_faccessat2
#    define __NR_faccessat2 439
#  endif
#  ifndef __NR_epoll_pwait2
#    define __NR_epoll_pwait2 441
#  endif
#  ifndef __NR_landlock_create_ruleset
#    define __NR_landlock_create_ruleset 444
#  endif
#  ifndef __NR_futex_waitv
#    define __NR_futex_waitv 449
#  endif
#  ifndef __NR_mseal
#    define __NR_mseal 462
#  endif
#endif

// ---- syscall probe --------------------------------------------------------------

typedef long (*probe_fn)(void);

static long p_set_robust_list(void) { return syscall(__NR_set_robust_list, NULL, 0); }
static long p_get_robust_list(void) {
    void *head; size_t len;
    return syscall(__NR_get_robust_list, 0, &head, &len);
}
static long p_rseq(void) { return syscall(__NR_rseq, NULL, 0, 0, 0); }
static long p_faccessat(void) { return syscall(__NR_faccessat, AT_FDCWD, "/", F_OK); }
static long p_faccessat2(void) { return syscall(__NR_faccessat2, AT_FDCWD, "/", F_OK, 0); }
static long p_shmget(void) {
    long id = syscall(__NR_shmget, 0 /*IPC_PRIVATE*/, 4096, 0600);
    if (id >= 0) syscall(__NR_shmctl, id, 0 /*IPC_RMID*/, NULL);
    return id;
}
static long p_semget(void) {
    long id = syscall(__NR_semget, 0, 1, 0600);
    if (id >= 0) syscall(__NR_semctl, id, 0, 0 /*IPC_RMID*/, 0);
    return id;
}
static long p_msgget(void) {
    long id = syscall(__NR_msgget, 0, 0600);
    if (id >= 0) syscall(__NR_msgctl, id, 0 /*IPC_RMID*/, NULL);
    return id;
}
static long p_futex_waitv(void) { return syscall(__NR_futex_waitv, NULL, 0, 0, NULL, 0); }
static long p_io_uring_setup(void) { return syscall(__NR_io_uring_setup, 0, NULL); }
static long p_clone3(void) { return syscall(__NR_clone3, NULL, 0); }
static long p_memfd_create(void) { return syscall(__NR_memfd_create, "probe", 0); }
static long p_pidfd_open(void) { return syscall(__NR_pidfd_open, getpid(), 0); }
static long p_close_range(void) { return syscall(__NR_close_range, 1000, 1000, 0); }
static long p_name_to_handle_at(void) { return syscall(__NR_name_to_handle_at, AT_FDCWD, "/", NULL, NULL, 0); }
static long p_landlock(void) { return syscall(__NR_landlock_create_ruleset, NULL, 0, 1 /*VERSION*/); }
static long p_unshare_user(void) { return syscall(__NR_unshare, 0x10000000 /*CLONE_NEWUSER*/); }
static long p_ptrace_traceme(void) { return syscall(__NR_ptrace, 0 /*PTRACE_TRACEME*/, 0, 0, 0); }
static long p_set_mempolicy(void) { return syscall(__NR_set_mempolicy, 0 /*MPOL_DEFAULT*/, NULL, 0); }
static long p_sched_setaffinity(void) {
    cpu_set_t set;
    if (sched_getaffinity(0, sizeof set, &set) != 0) return -1;
    return syscall(__NR_sched_setaffinity, 0, sizeof set, &set);
}
static long p_personality(void) { return syscall(__NR_personality, 0xffffffffUL); }
static long p_process_vm_readv(void) {
    char src[8] = "abcdefg", dst[8];
    struct iovec l = { dst, 8 }, r = { src, 8 };
    return syscall(__NR_process_vm_readv, getpid(), &l, 1, &r, 1, 0);
}
static long p_userfaultfd(void) { return syscall(__NR_userfaultfd, O_CLOEXEC); }
static long p_statx(void) {
    char buf[256];
    return syscall(__NR_statx, AT_FDCWD, "/", 0, 0x7ff /*STATX_BASIC_STATS*/, buf);
}
static long p_epoll_pwait2(void) { return syscall(__NR_epoll_pwait2, -1, NULL, 1, NULL, NULL, 0); }
static long p_openat2(void) {
    uint64_t how[3] = { O_RDONLY, 0, 0 };
    return syscall(__NR_openat2, AT_FDCWD, "/", how, sizeof how);
}
static long p_membarrier(void) { return syscall(__NR_membarrier, 0 /*QUERY*/, 0, 0); }
static long p_execveat(void) { return syscall(__NR_execveat, -1, "", NULL, NULL, 0); }
static long p_seccomp(void) {
    uint32_t action = 0x7ffc0000U; /* SECCOMP_RET_LOG */
    return syscall(__NR_seccomp, 2 /*SECCOMP_GET_ACTION_AVAIL*/, 0, &action);
}
static long p_kcmp(void) { return syscall(__NR_kcmp, getpid(), getpid(), 0 /*KCMP_FILE*/, 0, 0); }
static long p_setns(void) { return syscall(__NR_setns, -1, 0); }
static long p_mseal(void) { return syscall(__NR_mseal, NULL, 0, 0); }
static long p_chroot(void) { return syscall(__NR_chroot, "/"); }
static long p_mremap(void) {
    void *p = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (p == MAP_FAILED) return -1;
    return (long)syscall(__NR_mremap, p, 4096, 8192, 1 /*MREMAP_MAYMOVE*/, NULL);
}
static long p_prctl_set_vma(void) {
    void *p = mmap(NULL, 4096, PROT_READ, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    return syscall(__NR_prctl, 0x53564d41 /*PR_SET_VMA*/, 0 /*ANON_NAME*/, p, 4096, "probe");
}

static const struct { const char *name; const char *why; probe_fn fn; } kProbes[] = {
    { "set_robust_list",  "glibc: every thread start",          p_set_robust_list },
    { "get_robust_list",  "FEX: guest robust lists",            p_get_robust_list },
    { "rseq",             "glibc 2.35+: thread start",          p_rseq },
    { "faccessat",        "baseline",                           p_faccessat },
    { "faccessat2",       "glibc access()/eaccess()",           p_faccessat2 },
    { "shmget",           "SysV shm: X11 MIT-SHM, Steam",       p_shmget },
    { "semget",           "SysV sem",                           p_semget },
    { "msgget",           "SysV msg",                           p_msgget },
    { "futex_waitv",      "Proton/newer games",                 p_futex_waitv },
    { "io_uring_setup",   "some runtimes",                      p_io_uring_setup },
    { "clone3",           "glibc pthread_create",               p_clone3 },
    { "memfd_create",     "shared memory fallback",             p_memfd_create },
    { "pidfd_open",       "process mgmt",                       p_pidfd_open },
    { "close_range",      "glibc/posix_spawn",                  p_close_range },
    { "name_to_handle_at","systemd-style code",                 p_name_to_handle_at },
    { "landlock",         "sandboxing (CEF?)",                  p_landlock },
    { "unshare(USER)",    "pressure-vessel, CEF sandbox",       p_unshare_user },
    { "ptrace(TRACEME)",  "proot fallback",                     p_ptrace_traceme },
    { "set_mempolicy",    "NUMA-aware allocators",              p_set_mempolicy },
    { "sched_setaffinity","FEX/games: core pinning",            p_sched_setaffinity },
    { "personality",      "FEX: 32-bit guests",                 p_personality },
    { "process_vm_readv", "crash handlers",                     p_process_vm_readv },
    { "userfaultfd",      "",                                   p_userfaultfd },
    { "statx",            "glibc stat()",                       p_statx },
    { "epoll_pwait2",     "",                                   p_epoll_pwait2 },
    { "openat2",          "",                                   p_openat2 },
    { "membarrier",       "JIT code invalidation",              p_membarrier },
    { "execveat",         "fexecve()",                          p_execveat },
    { "seccomp",          "own filters (exec redirect)",        p_seccomp },
    { "kcmp",             "",                                   p_kcmp },
    { "setns",            "",                                   p_setns },
    { "mseal",            "glibc 2.41+ (future)",               p_mseal },
    { "chroot",           "expected blocked",                   p_chroot },
    { "mremap",           "FEX allocator",                      p_mremap },
    { "prctl(PR_SET_VMA)","FEX: naming JIT regions",            p_prctl_set_vma },
};

// Runs fn in a child. Result: >=0 exit code (0 = ok, else errno), <0 = -signal.
static int run_in_child(probe_fn fn) {
    pid_t pid = fork();
    if (pid < 0) return 1000 + errno;
    if (pid == 0) {
        // Default SIGSYS action so a seccomp trap shows up as a signal, not a
        // crash report from the app's own handler.
        signal(SIGSYS, SIG_DFL);
        long r = fn();
        _exit(r < 0 ? (errno & 0xff ? errno & 0xff : 255) : 0);
    }
    int st = 0;
    while (waitpid(pid, &st, __WALL) < 0 && errno == EINTR) {}
    if (WIFSIGNALED(st)) return -WTERMSIG(st);
    if (WIFSTOPPED(st)) { kill(pid, SIGKILL); waitpid(pid, &st, 0); return -WSTOPSIG(st); }
    return WEXITSTATUS(st);
}

static void describe(strbuf *out, int r) {
    if (r == 0) sb_printf(out, "ALLOWED");
    else if (r == -SIGSYS) sb_printf(out, "BLOCKED (SIGSYS)");
    else if (r < 0) sb_printf(out, "killed by signal %d (%s)", -r, strsignal(-r));
    else if (r >= 1000) sb_printf(out, "fork failed: %s", strerror(r - 1000));
    else sb_printf(out, "allowed, returned %s", strerror(r));
}

void probe_syscalls(strbuf *out) {
    for (size_t i = 0; i < sizeof kProbes / sizeof kProbes[0]; i++) {
        sb_printf(out, "%-20s ", kProbes[i].name);
        describe(out, run_in_child(kProbes[i].fn));
        if (kProbes[i].why[0]) sb_printf(out, "   [%s]", kProbes[i].why);
        sb_printf(out, "\n");
    }
}

// ---- W^X probes ---------------------------------------------------------------------

// Tiny arm64 function: "mov w0, #42; ret". Used to prove a mapping is executable.
static const uint32_t kRet42[] = { 0x52800540, 0xd65f03c0 };

static int call_code(void *code) {
    __builtin___clear_cache((char *)code, (char *)code + sizeof kRet42);
    return ((int (*)(void))code)();
}

static const char *g_path_arg;  // parameter for child probes

static long wx_anon_rw_then_rx(void) {
    void *p = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (p == MAP_FAILED) return -1;
    memcpy(p, kRet42, sizeof kRet42);
    if (mprotect(p, 4096, PROT_READ | PROT_EXEC) != 0) return -1;
#if defined(__aarch64__)
    if (call_code(p) != 42) { errno = EIO; return -1; }
#endif
    return 0;
}

static long wx_anon_rwx(void) {
    void *p = mmap(NULL, 4096, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (p == MAP_FAILED) return -1;
    memcpy(p, kRet42, sizeof kRet42);
#if defined(__aarch64__)
    if (call_code(p) != 42) { errno = EIO; return -1; }
#endif
    return 0;
}

// Dual mapping: RW view + RX view of the same memfd (a common JIT layout).
static long wx_memfd_dual(void) {
    int fd = (int)syscall(__NR_memfd_create, "jit", 0);
    if (fd < 0 || ftruncate(fd, 4096) != 0) return -1;
    void *rw = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (rw == MAP_FAILED) return -1;
    void *rx = mmap(NULL, 4096, PROT_READ | PROT_EXEC, MAP_SHARED, fd, 0);
    if (rx == MAP_FAILED) return -1;
    memcpy(rw, kRet42, sizeof kRet42);
#if defined(__aarch64__)
    if (call_code(rx) != 42) { errno = EIO; return -1; }
#endif
    return 0;
}

// What glibc's ld.so does for every library: mmap a regular file with PROT_EXEC.
static long wx_file_mmap_exec(void) {
    int fd = open(g_path_arg, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    void *p = mmap(NULL, 4096, PROT_READ | PROT_EXEC, MAP_PRIVATE, fd, 0);
    return p == MAP_FAILED ? -1 : 0;
}

// fork + execve; reports the execve errno through a CLOEXEC pipe.
// Returns 0 on successful exec (child exit code in *child_status), else errno.
static int try_exec(const char *path, char *const argv[], int *child_status) {
    int pfd[2];
    if (pipe2(pfd, O_CLOEXEC) != 0) return errno;
    pid_t pid = fork();
    if (pid < 0) return errno;
    if (pid == 0) {
        close(pfd[0]);
        int devnull = open("/dev/null", O_WRONLY);
        if (devnull >= 0) { dup2(devnull, 1); dup2(devnull, 2); }
        execv(path, argv);
        int e = errno;
        (void)!write(pfd[1], &e, sizeof e);
        _exit(127);
    }
    close(pfd[1]);
    int e = 0;
    ssize_t n;
    while ((n = read(pfd[0], &e, sizeof e)) < 0 && errno == EINTR) {}
    close(pfd[0]);
    int st = 0;
    waitpid(pid, &st, 0);
    *child_status = st;
    return n == (ssize_t)sizeof e ? e : 0;
}

static void report_exec(strbuf *out, const char *label, const char *path, char *const argv[]) {
    int st = 0;
    int e = try_exec(path, argv, &st);
    sb_printf(out, "%-34s ", label);
    if (e) sb_printf(out, "DENIED: %s\n", strerror(e));
    else if (WIFSIGNALED(st)) sb_printf(out, "exec ok, but child killed by signal %d (%s)\n", WTERMSIG(st), strsignal(WTERMSIG(st)));
    else sb_printf(out, "ALLOWED (exit %d)\n", WEXITSTATUS(st));
}

static int copy_file(const char *src, const char *dst, mode_t mode) {
    int in = open(src, O_RDONLY | O_CLOEXEC);
    if (in < 0) return -1;
    int outfd = open(dst, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, mode);
    if (outfd < 0) { close(in); return -1; }
    char buf[65536];
    ssize_t n;
    int rc = 0;
    while ((n = read(in, buf, sizeof buf)) > 0)
        if (write(outfd, buf, (size_t)n) != n) { rc = -1; break; }
    close(in);
    close(outfd);
    chmod(dst, mode);
    return rc;
}

void probe_wx(strbuf *out, const char *data_dir, const char *native_lib_dir) {
    sb_printf(out, "%-34s ", "anon mmap RW -> mprotect RX (JIT)");
    describe(out, run_in_child(wx_anon_rw_then_rx)); sb_printf(out, "\n");
    sb_printf(out, "%-34s ", "anon mmap RWX");
    describe(out, run_in_child(wx_anon_rwx)); sb_printf(out, "\n");
    sb_printf(out, "%-34s ", "memfd dual map RW+RX");
    describe(out, run_in_child(wx_memfd_dual)); sb_printf(out, "\n");

    // Copy a known-good system executable into app data and try to exec/mmap it.
    char copy[512];
    snprintf(copy, sizeof copy, "%s/probe-toybox", data_dir);
    if (copy_file("/system/bin/toybox", copy, 0755) != 0) {
        sb_printf(out, "could not copy toybox to %s: %s\n", copy, strerror(errno));
    } else {
        g_path_arg = copy;
        sb_printf(out, "%-34s ", "mmap(PROT_EXEC) of file in app data");
        describe(out, run_in_child(wx_file_mmap_exec)); sb_printf(out, "\n");
        char *argv[] = { "toybox", "true", NULL };
        report_exec(out, "execve() of file in app data", copy, argv);
        unlink(copy);
    }

    char *targv[] = { "toybox", "true", NULL };
    report_exec(out, "execve() /system/bin/toybox", "/system/bin/toybox", targv);

    // Executables packaged as lib*.so land in nativeLibraryDir (apk_data_file).
    char nl[512];
    snprintf(nl, sizeof nl, "%s/libfxprobe.so", native_lib_dir);
    if (access(nl, F_OK) == 0) {
        char *argv[] = { "fxprobe", NULL };
        report_exec(out, "execve() from nativeLibraryDir", nl, argv);
        g_path_arg = nl;
        sb_printf(out, "%-34s ", "mmap(PROT_EXEC) from nativeLibraryDir");
        describe(out, run_in_child(wx_file_mmap_exec)); sb_printf(out, "\n");
    } else {
        sb_printf(out, "%-34s not packaged (%s)\n", "execve() from nativeLibraryDir", nl);
    }
}

// ---- KGSL ---------------------------------------------------------------------------
// Minimal copy of the msm_kgsl.h UAPI bits we need.
struct kgsl_devinfo {
    unsigned int device_id;
    unsigned int chip_id;
    unsigned int mmu_enabled;
    unsigned long gmem_gpubaseaddr;
    unsigned int gpu_id;
    size_t gmem_sizebytes;
};
struct kgsl_device_getproperty {
    unsigned int type;
    void *value;
    size_t sizebytes;
};
#define KGSL_IOC_TYPE 0x09
#define IOCTL_KGSL_DEVICE_GETPROPERTY _IOWR(KGSL_IOC_TYPE, 0x2, struct kgsl_device_getproperty)
#define KGSL_PROP_DEVICE_INFO 0x1

void probe_kgsl(strbuf *out) {
    int fd = open("/dev/kgsl-3d0", O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        sb_printf(out, "open /dev/kgsl-3d0: FAILED (%s)\n", strerror(errno));
        return;
    }
    sb_printf(out, "open /dev/kgsl-3d0: OK\n");
    struct kgsl_devinfo info;
    memset(&info, 0, sizeof info);
    struct kgsl_device_getproperty gp = { KGSL_PROP_DEVICE_INFO, &info, sizeof info };
    if (ioctl(fd, IOCTL_KGSL_DEVICE_GETPROPERTY, &gp) == 0) {
        sb_printf(out, "device_id=%u chip_id=0x%08x gpu_id=%u mmu=%u gmem=%zu KiB\n",
                  info.device_id, info.chip_id, info.gpu_id, info.mmu_enabled,
                  info.gmem_sizebytes / 1024);
    } else {
        sb_printf(out, "KGSL_PROP_DEVICE_INFO ioctl failed: %s\n", strerror(errno));
    }
    close(fd);
}

// ---- virtual address size -------------------------------------------------------------
// Same method FEX uses (FEXCore/Source/Utils/Allocator.cpp, GetHostVABits): try to
// map the last page below 2^bits; success or EEXIST means that range exists.
void probe_va(strbuf *out) {
    static const int kBits[] = { 57, 52, 48, 47, 42, 39, 36 };
    long page = sysconf(_SC_PAGESIZE);
    int found = 0;
    for (size_t i = 0; i < sizeof kBits / sizeof *kBits; i++) {
        void *addr = (void *)((1ULL << kBits[i]) - (unsigned long long)page);
        void *p = mmap(addr, (size_t)page, PROT_NONE,
                       MAP_FIXED_NOREPLACE | MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        int e = errno;
        if (p != MAP_FAILED) munmap(p, (size_t)page);
        if (p != MAP_FAILED || e == EEXIST) { found = kBits[i]; break; }
    }
    sb_printf(out, "host VA bits     %d  (%s)\n", found,
              found >= 48 ? "FEX 48-bit allocator available"
              : found >= 47 ? "full x86-64 guest range fits"
              : "x86-64 guests get a reduced address range");
}
