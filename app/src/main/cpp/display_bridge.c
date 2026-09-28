// Display bridge (PLAN.md D3): shows Xvfb's framebuffer in an Android Surface.
//
// Xvfb runs inside the rootfs with -shmem, so its framebuffer (an XWD image) lives
// in a SysV shm segment. Our glibc implements SysV shm over memfd + fxshmd
// (tools/fxshmd); this bionic code speaks the same small protocol to get the
// segment's memfd, maps it read-only and copies frames into an ANativeWindow.
// Simple full-frame copy at a fixed rate; damage tracking can come later.
//
// A game whose Mesa has the fxpresent patch sends its frames here directly
// (tools/fxpresent/fxpresent-proto.h): they are shown as they come and Xvfb's
// framebuffer is looked at only while no such frames arrive.
#include <android/log.h>
#include <android/native_window_jni.h>
#include <errno.h>
#include <jni.h>
#include <poll.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include "../../../../tools/fxshmd/fxshm-proto.h"
#include "../../../../tools/fxpresent/fxpresent-proto.h"

#define TAG "fexdroid-display"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// XWD header fields we need (all big-endian CARD32, in this order).
enum { XWD_HEADER_SIZE, XWD_FILE_VERSION, XWD_PIXMAP_FORMAT, XWD_PIXMAP_DEPTH, XWD_PIXMAP_WIDTH,
       XWD_PIXMAP_HEIGHT, XWD_XOFFSET, XWD_BYTE_ORDER, XWD_BITMAP_UNIT, XWD_BITMAP_BIT_ORDER,
       XWD_BITMAP_PAD, XWD_BITS_PER_PIXEL, XWD_BYTES_PER_LINE, XWD_VISUAL_CLASS, XWD_RED_MASK,
       XWD_GREEN_MASK, XWD_BLUE_MASK, XWD_BITS_PER_RGB, XWD_COLORMAP_ENTRIES, XWD_NCOLORS };
#define XWD_COLOR_SIZE 12

struct bridge {
    int sock;                // fxshmd connection (keeps the attachment alive)
    const uint8_t *seg;
    size_t seg_size;
    ANativeWindow *win;
    pthread_t thread;
    atomic_bool running;
    int fps;
    atomic_long frames;
    atomic_long changed;     // frames whose content differs from the one before (= frames shown)
    int listen;              // fxpresent: the socket games connect to
    int client;              // the game that sends frames now
    uint8_t *mem;            // its memory
    size_t mem_size;
    struct fxpresent_hello hello;
    atomic_long direct;      // frames shown from it
};

static struct bridge g = { .sock = -1, .listen = -1, .client = -1 };

#define LOOK_STEP 8

static uint32_t be32(const uint8_t *p) {
    return (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3];
}

// ATTACH request; returns the memfd (and the segment size) or -1.
static int fxshm_attach(const char *sock_path, int shmid, int *sock_out, size_t *size_out) {
    int s = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (s < 0) return -1;
    struct sockaddr_un sa = { .sun_family = AF_UNIX };
    if (strlen(sock_path) >= sizeof sa.sun_path) { close(s); errno = ENAMETOOLONG; return -1; }
    strcpy(sa.sun_path, sock_path);
    if (connect(s, (struct sockaddr *)&sa, sizeof sa) != 0) { close(s); return -1; }

    struct fxshm_req req = { .version = FXSHM_PROTO_VERSION, .op = FXSHM_OP_ATTACH,
                             .id = shmid, .flags = 010000 /* SHM_RDONLY */, .pid = getpid() };
    if (send(s, &req, sizeof req, MSG_NOSIGNAL) != (ssize_t)sizeof req) { close(s); return -1; }

    struct fxshm_resp resp;
    char cbuf[CMSG_SPACE(sizeof(int))];
    struct iovec iov = { &resp, sizeof resp };
    struct msghdr msg = { .msg_iov = &iov, .msg_iovlen = 1, .msg_control = cbuf, .msg_controllen = sizeof cbuf };
    ssize_t n = recvmsg(s, &msg, MSG_CMSG_CLOEXEC);
    if (n != (ssize_t)sizeof resp) { close(s); errno = EPROTO; return -1; }
    if (resp.result < 0) { close(s); errno = resp.err; return -1; }
    int fd = -1;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c))
        if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS) memcpy(&fd, CMSG_DATA(c), sizeof fd);
    if (fd < 0) { close(s); errno = EPROTO; return -1; }
    *sock_out = s;
    *size_out = resp.seg.segsz;
    return fd;
}

static long long now_ns(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000000000LL + t.tv_nsec;
}

static void direct_close(void) {
    if (g.mem) { munmap(g.mem, g.mem_size); g.mem = NULL; }
    if (g.client >= 0) { close(g.client); g.client = -1; LOGI("direct frames: the game is gone"); }
}

// A game connected: its hello and its memory. The newest game wins.
static void direct_accept(void) {
    int c = accept4(g.listen, NULL, NULL, SOCK_CLOEXEC | SOCK_NONBLOCK);
    if (c < 0) return;
    struct fxpresent_hello hello;
    char cbuf[CMSG_SPACE(sizeof(int))];
    struct iovec iov = { &hello, sizeof hello };
    struct msghdr msg = { .msg_iov = &iov, .msg_iovlen = 1, .msg_control = cbuf, .msg_controllen = sizeof cbuf };
    struct pollfd p = { c, POLLIN, 0 };
    int fd = -1;
    if (poll(&p, 1, 200) == 1 && recvmsg(c, &msg, MSG_CMSG_CLOEXEC) == (ssize_t)sizeof hello)
        for (struct cmsghdr *m = CMSG_FIRSTHDR(&msg); m; m = CMSG_NXTHDR(&msg, m))
            if (m->cmsg_level == SOL_SOCKET && m->cmsg_type == SCM_RIGHTS) memcpy(&fd, CMSG_DATA(m), sizeof fd);
    const uint64_t need = (uint64_t)hello.first_frame + (uint64_t)hello.frame_size * hello.slots;
    if (fd < 0 || hello.magic != FXPRESENT_MAGIC || hello.version != FXPRESENT_VERSION || hello.slots != FXPRESENT_SLOTS ||
        hello.width == 0 || hello.height == 0 || hello.width > 16384 || hello.height > 16384 ||
        hello.stride < hello.width * 4 || hello.first_frame < sizeof(struct fxpresent_shared) ||
        (uint64_t)hello.stride * hello.height > hello.frame_size || need > (1ull << 31)) {
        LOGE("direct frames: a hello that makes no sense");
        if (fd >= 0) close(fd);
        close(c);
        return;
    }
    void *m = mmap(NULL, need, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (m == MAP_FAILED) { LOGE("direct frames: mmap of %llu bytes: %s", (unsigned long long)need, strerror(errno)); close(c); return; }
    direct_close();
    g.client = c;
    g.mem = m;
    g.mem_size = need;
    g.hello = hello;
    LOGI("direct frames from process %d: %ux%u, %u bytes a row", hello.pid, hello.width, hello.height, hello.stride);
}

// The newest frame of the game, at its place on the X screen of w x ht. False: nothing shown.
static bool direct_show(uint32_t w, uint32_t ht) {
    struct fxpresent_shared *sh = (struct fxpresent_shared *)g.mem;
    uint32_t slot;
    for (;;) {
        slot = atomic_load(&sh->published);
        if (slot >= FXPRESENT_SLOTS) return false;
        atomic_store(&sh->reading, slot);
        if (atomic_load(&sh->published) == slot) break; // else the game went on: take the newer one
    }
    const int32_t fx = atomic_load(&sh->x), fy = atomic_load(&sh->y);
    ANativeWindow_Buffer buf;
    bool shown = false;
    if (ANativeWindow_lock(g.win, &buf, NULL) == 0) {
        const uint8_t *frame = g.mem + g.hello.first_frame + (size_t)slot * g.hello.frame_size;
        const uint32_t rows = (uint32_t)buf.height < ht ? (uint32_t)buf.height : ht;
        const uint32_t cols = (uint32_t)buf.width < w ? (uint32_t)buf.width : w;
        for (uint32_t y = 0; y < rows; y++) {
            uint32_t *dst = (uint32_t *)buf.bits + (size_t)y * (size_t)buf.stride;
            const int64_t sy = (int64_t)y - fy;
            if (sy < 0 || sy >= g.hello.height) { for (uint32_t x = 0; x < cols; x++) dst[x] = 0xff000000u; continue; }
            const uint32_t *src = (const uint32_t *)(frame + (size_t)sy * g.hello.stride);
            for (uint32_t x = 0; x < cols; x++) {
                const int64_t sx = (int64_t)x - fx;
                if (sx < 0 || sx >= g.hello.width) { dst[x] = 0xff000000u; continue; }
                const uint32_t p = src[sx];
                dst[x] = (p & 0xff00ff00u) | ((p & 0xffu) << 16) | ((p >> 16) & 0xffu) | 0xff000000u;
            }
        }
        ANativeWindow_unlockAndPost(g.win);
        shown = true;
    }
    atomic_store(&sh->reading, FXPRESENT_NONE);
    return shown;
}

// Waits up to wait_ms for a game's frame and shows it. True: one was shown.
static bool direct_turn(uint32_t w, uint32_t ht, int wait_ms) {
    struct pollfd p[2] = { { g.listen, POLLIN, 0 }, { g.client, POLLIN, 0 } };
    if (poll(p, 2, wait_ms) <= 0) return false;
    if (p[0].revents & POLLIN) direct_accept();
    if (g.client < 0 || g.client != p[1].fd || !(p[1].revents & (POLLIN | POLLHUP | POLLERR))) return false;
    struct fxpresent_frame f;
    bool any = false;
    for (;;) { // Only the newest frame matters.
        ssize_t n = recv(g.client, &f, sizeof f, MSG_DONTWAIT);
        if (n == (ssize_t)sizeof f) { any = true; continue; }
        if (n == 0 || (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)) { direct_close(); return false; }
        break;
    }
    return any && direct_show(w, ht);
}

static void *render_loop(void *arg) {
    (void)arg;
    const uint8_t *h = g.seg;
    uint32_t hdr = be32(h + 4 * XWD_HEADER_SIZE);
    uint32_t w = be32(h + 4 * XWD_PIXMAP_WIDTH), ht = be32(h + 4 * XWD_PIXMAP_HEIGHT);
    uint32_t bpp = be32(h + 4 * XWD_BITS_PER_PIXEL), stride = be32(h + 4 * XWD_BYTES_PER_LINE);
    uint32_t ncolors = be32(h + 4 * XWD_NCOLORS);
    size_t off = hdr + (size_t)ncolors * XWD_COLOR_SIZE;
    LOGI("framebuffer %ux%u bpp=%u stride=%u pixels at +%zu (segment %zu bytes)", w, ht, bpp, stride, off, g.seg_size);
    if (bpp != 32 || off + (size_t)stride * ht > g.seg_size) {
        LOGE("unsupported framebuffer layout");
        return NULL;
    }
    ANativeWindow_setBuffersGeometry(g.win, (int32_t)w, (int32_t)ht, AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM);
    const int target = g.fps > 0 ? g.fps : 30;
    const long frame_ns = 1000000000L / target;
    uint32_t last_sum[LOOK_STEP] = {0};
    uint32_t phase = 0;
    bool shown = false;
    long looks = 0, logged = 0;
    long long last_direct = 0, last_log = now_ns();
    long direct_logged = 0;
    while (atomic_load(&g.running)) {
        // A game that sends its frames itself: they are shown as they come, and the X
        // screen is looked at only after half a second without any (the game is loading,
        // or something else is on the screen).
        if (g.client >= 0) {
            if (direct_turn(w, ht, 50)) {
                last_direct = now_ns();
                atomic_fetch_add(&g.frames, 1);
                atomic_fetch_add(&g.changed, 1);
                atomic_fetch_add(&g.direct, 1);
                shown = false; // The X screen is drawn again when its turn comes.
            }
            if (now_ns() - last_log >= 10000000000LL) {
                long c = atomic_load(&g.direct);
                LOGI("last 10 s: %ld frames straight from the game", c - direct_logged);
                direct_logged = c;
                last_log = now_ns();
            }
            if (g.client >= 0 && now_ns() - last_direct < 500000000LL) continue;
        } else if (g.listen >= 0) {
            direct_turn(w, ht, 0);
        }
        struct timespec t0;
        clock_gettime(CLOCK_MONOTONIC, &t0);
        // Look first, copy only what is new: a game at 25 frames/s costs 25 copies a second
        // whatever the rate here is, and a still screen costs none. Looking reads one row in
        // LOOK_STEP, a different one every time, so a change anywhere is seen within
        // LOOK_STEP looks; a game redraws most rows with every frame.
        uint32_t sum = 0;
        for (uint32_t y = phase; y < ht; y += LOOK_STEP) {
            const uint32_t *src = (const uint32_t *)(g.seg + off + (size_t)y * stride);
            uint32_t row = 0;
            for (uint32_t x = 0; x < w; x++) row += src[x];
            sum = sum * 31 + row;
        }
        const bool changed = !shown || sum != last_sum[phase];
        phase = (phase + 1) % LOOK_STEP;
        ANativeWindow_Buffer buf;
        if (changed && ANativeWindow_lock(g.win, &buf, NULL) == 0) {
            uint32_t rows = (uint32_t)buf.height < ht ? (uint32_t)buf.height : ht;
            uint32_t cols = (uint32_t)buf.width < w ? (uint32_t)buf.width : w;
            // The sums of what is copied, for every phase: the next looks compare with the
            // frame on the screen, not with what was there LOOK_STEP looks ago.
            memset(last_sum, 0, sizeof last_sum);
            for (uint32_t y = 0; y < ht; y++) {
                const uint32_t *src = (const uint32_t *)(g.seg + off + (size_t)y * stride);
                uint32_t row = 0, x = 0;
                if (y < rows) {
                    uint32_t *dst = (uint32_t *)buf.bits + (size_t)y * (size_t)buf.stride;
                    // X server memory is B,G,R,X; the window wants R,G,B,X: swap R and B.
                    for (; x < cols; x++) {
                        uint32_t p = src[x];
                        dst[x] = (p & 0xff00ff00u) | ((p & 0xffu) << 16) | ((p >> 16) & 0xffu) | 0xff000000u;
                        row += p;
                    }
                }
                for (; x < w; x++) row += src[x];
                last_sum[y % LOOK_STEP] = last_sum[y % LOOK_STEP] * 31 + row;
            }
            ANativeWindow_unlockAndPost(g.win);
            atomic_fetch_add(&g.frames, 1);
            atomic_fetch_add(&g.changed, 1);
            shown = true;
        }
        if (++looks % (10L * target) == 0) {
            long c = atomic_load(&g.changed);
            LOGI("last %ld frames looked at: %ld had new content", 10L * target, c - logged);
            logged = c;
        }
        struct timespec t1;
        clock_gettime(CLOCK_MONOTONIC, &t1);
        long spent = (t1.tv_sec - t0.tv_sec) * 1000000000L + (t1.tv_nsec - t0.tv_nsec);
        if (spent < frame_ns) {
            struct timespec d = { 0, frame_ns - spent };
            nanosleep(&d, NULL);
        }
    }
    return NULL;
}

static void stop_bridge(void) {
    if (atomic_exchange(&g.running, false)) pthread_join(g.thread, NULL);
    direct_close();
    if (g.listen >= 0) { close(g.listen); g.listen = -1; }
    if (g.seg) { munmap((void *)g.seg, g.seg_size); g.seg = NULL; }
    if (g.sock >= 0) { close(g.sock); g.sock = -1; }
    if (g.win) { ANativeWindow_release(g.win); g.win = NULL; }
}

JNIEXPORT jstring JNICALL
Java_ro_cobrabm_fexdroid_DisplayBridge_start(JNIEnv *env, jclass cls, jobject surface, jstring sockPath, jint shmid, jint fps,
                                             jstring presentPath) {
    (void)cls;
    stop_bridge();
    // Where games send their frames (fxpresent); an empty path: nowhere.
    const char *pp = (*env)->GetStringUTFChars(env, presentPath, NULL);
    struct sockaddr_un pa = { .sun_family = AF_UNIX };
    if (pp[0] && strlen(pp) < sizeof pa.sun_path) {
        strcpy(pa.sun_path, pp);
        unlink(pp);
        g.listen = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
        if (g.listen >= 0 && (bind(g.listen, (struct sockaddr *)&pa, sizeof pa) != 0 || listen(g.listen, 4) != 0)) {
            LOGE("direct frames: socket %s: %s", pp, strerror(errno));
            close(g.listen);
            g.listen = -1;
        }
    }
    (*env)->ReleaseStringUTFChars(env, presentPath, pp);
    const char *sp = (*env)->GetStringUTFChars(env, sockPath, NULL);
    size_t size = 0;
    int sock = -1;
    int fd = fxshm_attach(sp, shmid, &sock, &size);
    (*env)->ReleaseStringUTFChars(env, sockPath, sp);
    char msg[256];
    if (fd < 0) {
        snprintf(msg, sizeof msg, "fxshmd attach(shmid %d) failed: %s", shmid, strerror(errno));
        return (*env)->NewStringUTF(env, msg);
    }
    void *p = mmap(NULL, size, PROT_READ, MAP_SHARED, fd, 0);
    close(fd);
    if (p == MAP_FAILED) {
        snprintf(msg, sizeof msg, "mmap of %zu bytes failed: %s", size, strerror(errno));
        close(sock);
        return (*env)->NewStringUTF(env, msg);
    }
    g.sock = sock;
    g.seg = p;
    g.seg_size = size;
    g.win = ANativeWindow_fromSurface(env, surface);
    g.fps = fps;
    atomic_store(&g.frames, 0);
    atomic_store(&g.changed, 0);
    atomic_store(&g.direct, 0);
    atomic_store(&g.running, true);
    pthread_create(&g.thread, NULL, render_loop, NULL);
    snprintf(msg, sizeof msg, "bridge running: segment %zu bytes", size);
    return (*env)->NewStringUTF(env, msg);
}

JNIEXPORT void JNICALL
Java_ro_cobrabm_fexdroid_DisplayBridge_stop(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    stop_bridge();
}

JNIEXPORT jlong JNICALL
Java_ro_cobrabm_fexdroid_DisplayBridge_frames(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return atomic_load(&g.frames);
}

JNIEXPORT jlong JNICALL
Java_ro_cobrabm_fexdroid_DisplayBridge_changedFrames(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return atomic_load(&g.changed);
}

/** Frames shown straight from a game (fxpresent) since the bridge started. */
JNIEXPORT jlong JNICALL
Java_ro_cobrabm_fexdroid_DisplayBridge_directFrames(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return atomic_load(&g.direct);
}
