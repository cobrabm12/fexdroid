// Phase 4 input: a tiny X11 client that injects Android input into Xvfb through the
// XTEST extension. No libX11 (this is bionic code in the app process): it speaks
// the handful of protocol requests it needs directly.
//   connection setup -> root window
//   QueryExtension("XTEST") -> major opcode
//   XTestFakeInput (key / button / motion, absolute or relative)
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#define TAG "fexdroid-input"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

enum { X_KeyPress = 2, X_KeyRelease = 3, X_ButtonPress = 4, X_ButtonRelease = 5, X_MotionNotify = 6 };

static struct {
    int fd;
    uint32_t root;
    uint8_t xtest_opcode;
    pthread_mutex_t lock;
} x = { .fd = -1, .lock = PTHREAD_MUTEX_INITIALIZER };

static int read_full(int fd, void *buf, size_t n) {
    uint8_t *p = buf;
    while (n) {
        ssize_t r = read(fd, p, n);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) return -1;
        p += r;
        n -= (size_t)r;
    }
    return 0;
}

static int write_full(int fd, const void *buf, size_t n) {
    const uint8_t *p = buf;
    while (n) {
        ssize_t r = send(fd, p, n, MSG_NOSIGNAL);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) return -1;
        p += r;
        n -= (size_t)r;
    }
    return 0;
}

// Replies/errors/events we don't care about would pile up in the socket; drop them.
static void drain(void) {
    uint8_t junk[4096];
    while (recv(x.fd, junk, sizeof junk, MSG_DONTWAIT) > 0) {}
}

static int x_connect(int display, char *err, size_t errlen) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_un sa = { .sun_family = AF_UNIX };
    // Abstract socket "@/tmp/.X11-unix/X<n>": Xvfb creates it even without /tmp.
    int n = snprintf(sa.sun_path + 1, sizeof sa.sun_path - 1, "/tmp/.X11-unix/X%d", display);
    socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + n);
    if (connect(fd, (struct sockaddr *)&sa, len) != 0) {
        snprintf(err, errlen, "connect @/tmp/.X11-unix/X%d: %s", display, strerror(errno));
        close(fd);
        return -1;
    }
    // Setup request: little-endian, protocol 11.0, no authorization (Xvfb -ac).
    uint8_t setup[12] = { 'l', 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    uint8_t head[8];
    if (write_full(fd, setup, sizeof setup) || read_full(fd, head, sizeof head)) {
        snprintf(err, errlen, "X setup I/O failed");
        close(fd);
        return -1;
    }
    uint16_t extra_words = (uint16_t)(head[6] | head[7] << 8);
    uint8_t body[65536];
    if ((size_t)extra_words * 4 > sizeof body || read_full(fd, body, (size_t)extra_words * 4)) {
        snprintf(err, errlen, "X setup reply too large/short");
        close(fd);
        return -1;
    }
    if (head[0] != 1) {
        snprintf(err, errlen, "X setup refused: %.*s", head[1], body);
        close(fd);
        return -1;
    }
    uint16_t vendor_len = (uint16_t)(body[16] | body[17] << 8);
    uint8_t nformats = body[21];
    size_t off = 32 + ((vendor_len + 3u) & ~3u) + 8u * nformats;
    memcpy(&x.root, body + off, 4);  // first SCREEN: root window id
    x.fd = fd;

    // QueryExtension("XTEST")
    const char name[] = "XTEST";
    uint8_t q[16] = { 98, 0, 4, 0, 5, 0, 0, 0 };
    memcpy(q + 8, name, 5);
    uint8_t rep[32];
    if (write_full(fd, q, sizeof q) || read_full(fd, rep, sizeof rep) || rep[0] != 1 || !rep[8]) {
        snprintf(err, errlen, "XTEST extension not available");
        close(fd);
        x.fd = -1;
        return -1;
    }
    x.xtest_opcode = rep[9];
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK);
    snprintf(err, errlen, "X input connected: root=0x%x xtest=%u", x.root, x.xtest_opcode);
    return 0;
}

// XTestFakeInput: 36-byte request (xtestproto.h, xXTestFakeInputReq).
static void fake_input(uint8_t type, uint8_t detail, int16_t rx, int16_t ry) {
    uint8_t r[36] = { 0 };
    r[0] = x.xtest_opcode;
    r[1] = 2;       // X_XTestFakeInput
    r[2] = 9;       // length in 4-byte units
    r[4] = type;
    r[5] = detail;
    // time (8..11) = CurrentTime, root (12..15) = None -> the pointer's screen
    memcpy(r + 24, &rx, 2);
    memcpy(r + 26, &ry, 2);
    pthread_mutex_lock(&x.lock);
    if (x.fd >= 0) {
        // Non-blocking socket: retry briefly if the server is slow to read.
        const uint8_t *p = r;
        size_t n = sizeof r;
        int spins = 0;
        while (n && spins < 1000) {
            ssize_t w = send(x.fd, p, n, MSG_NOSIGNAL);
            if (w > 0) { p += w; n -= (size_t)w; continue; }
            if (w < 0 && (errno == EAGAIN || errno == EINTR)) { drain(); usleep(200); spins++; continue; }
            close(x.fd);
            x.fd = -1;
            break;
        }
        if (x.fd >= 0) drain();
    }
    pthread_mutex_unlock(&x.lock);
}

JNIEXPORT jstring JNICALL
Java_ro_cobrabm_fexdroid_XInput_connect(JNIEnv *env, jclass cls, jint display) {
    (void)cls;
    char msg[256];
    pthread_mutex_lock(&x.lock);
    if (x.fd >= 0) { close(x.fd); x.fd = -1; }
    x_connect(display, msg, sizeof msg);
    pthread_mutex_unlock(&x.lock);
    LOGI("%s", msg);
    return (*env)->NewStringUTF(env, msg);
}

JNIEXPORT void JNICALL
Java_ro_cobrabm_fexdroid_XInput_key(JNIEnv *env, jclass cls, jint keycode, jboolean down) {
    (void)env; (void)cls;
    if (keycode >= 8 && keycode <= 255) fake_input(down ? X_KeyPress : X_KeyRelease, (uint8_t)keycode, 0, 0);
}

JNIEXPORT void JNICALL
Java_ro_cobrabm_fexdroid_XInput_button(JNIEnv *env, jclass cls, jint button, jboolean down) {
    (void)env; (void)cls;
    fake_input(down ? X_ButtonPress : X_ButtonRelease, (uint8_t)button, 0, 0);
}

JNIEXPORT void JNICALL
Java_ro_cobrabm_fexdroid_XInput_moveTo(JNIEnv *env, jclass cls, jint px, jint py) {
    (void)env; (void)cls;
    fake_input(X_MotionNotify, 0 /* absolute */, (int16_t)px, (int16_t)py);
}

JNIEXPORT void JNICALL
Java_ro_cobrabm_fexdroid_XInput_moveBy(JNIEnv *env, jclass cls, jint dx, jint dy) {
    (void)env; (void)cls;
    if (dx || dy) fake_input(X_MotionNotify, 1 /* relative */, (int16_t)dx, (int16_t)dy);
}
