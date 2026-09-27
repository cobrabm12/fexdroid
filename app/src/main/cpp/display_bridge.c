// Display bridge (PLAN.md D3): shows Xvfb's framebuffer in an Android Surface.
//
// Xvfb runs inside the rootfs with -shmem, so its framebuffer (an XWD image) lives
// in a SysV shm segment. Our glibc implements SysV shm over memfd + fxshmd
// (tools/fxshmd); this bionic code speaks the same small protocol to get the
// segment's memfd, maps it read-only and copies frames into an ANativeWindow.
// Simple full-frame copy at a fixed rate; damage tracking can come later.
#include <android/log.h>
#include <android/native_window_jni.h>
#include <errno.h>
#include <jni.h>
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
};

static struct bridge g = { .sock = -1 };

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
    uint32_t last_sum = 0;
    bool shown = false;
    long looks = 0, logged = 0;
    while (atomic_load(&g.running)) {
        struct timespec t0;
        clock_gettime(CLOCK_MONOTONIC, &t0);
        // Look first, copy only what is new: a game at 25 frames/s costs 25 copies a second
        // whatever the rate here is, and a still screen costs none.
        uint32_t sum = 0;
        for (uint32_t y = 0; y < ht; y++) {
            const uint32_t *src = (const uint32_t *)(g.seg + off + (size_t)y * stride);
            uint32_t row = 0;
            for (uint32_t x = 0; x < w; x++) row += src[x];
            sum = sum * 31 + row;
        }
        ANativeWindow_Buffer buf;
        if ((!shown || sum != last_sum) && ANativeWindow_lock(g.win, &buf, NULL) == 0) {
            uint32_t rows = (uint32_t)buf.height < ht ? (uint32_t)buf.height : ht;
            uint32_t cols = (uint32_t)buf.width < w ? (uint32_t)buf.width : w;
            for (uint32_t y = 0; y < rows; y++) {
                const uint32_t *src = (const uint32_t *)(g.seg + off + (size_t)y * stride);
                uint32_t *dst = (uint32_t *)buf.bits + (size_t)y * (size_t)buf.stride;
                // X server memory is B,G,R,X; the window wants R,G,B,X: swap R and B.
                for (uint32_t x = 0; x < cols; x++) {
                    uint32_t p = src[x];
                    dst[x] = (p & 0xff00ff00u) | ((p & 0xffu) << 16) | ((p >> 16) & 0xffu) | 0xff000000u;
                }
            }
            ANativeWindow_unlockAndPost(g.win);
            atomic_fetch_add(&g.frames, 1);
            atomic_fetch_add(&g.changed, 1);
            last_sum = sum;
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
    if (g.seg) { munmap((void *)g.seg, g.seg_size); g.seg = NULL; }
    if (g.sock >= 0) { close(g.sock); g.sock = -1; }
    if (g.win) { ANativeWindow_release(g.win); g.win = NULL; }
}

JNIEXPORT jstring JNICALL
Java_ro_cobrabm_fexdroid_DisplayBridge_start(JNIEnv *env, jclass cls, jobject surface, jstring sockPath, jint shmid, jint fps) {
    (void)cls;
    stop_bridge();
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
