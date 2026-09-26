// Phase 4 audio: PulseAudio in the rootfs renders into a FIFO (module-pipe-sink,
// s16le, 48 kHz, stereo); this thread reads the FIFO and plays it with AAudio.
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#define TAG "fexdroid-audio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define RATE 48000
#define CHANNELS 2
#define FRAME_BYTES (2 * CHANNELS)

static struct {
    char fifo[512];
    pthread_t thread;
    atomic_bool running;
    atomic_long frames;
    AAudioStream *stream;
} a;

static AAudioStream *open_stream(void) {
    AAudioStreamBuilder *b = NULL;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return NULL;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(b, CHANNELS);
    AAudioStreamBuilder_setSampleRate(b, RATE);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_GAME);
    AAudioStream *s = NULL;
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &s);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) { LOGE("openStream: %s", AAudio_convertResultToText(r)); return NULL; }
    AAudioStream_requestStart(s);
    LOGI("AAudio stream: %d Hz, burst %d frames", AAudioStream_getSampleRate(s), AAudioStream_getFramesPerBurst(s));
    return s;
}

static void *pump(void *arg) {
    (void)arg;
    uint8_t buf[FRAME_BYTES * 1024];
    size_t have = 0;
    while (atomic_load(&a.running)) {
        // Blocks until PulseAudio opens the FIFO for writing; reopen when it closes.
        int fd = open(a.fifo, O_RDONLY | O_CLOEXEC);
        if (fd < 0) { LOGE("open %s: %s", a.fifo, strerror(errno)); sleep(1); continue; }
        LOGI("FIFO opened, streaming");
        for (;;) {
            ssize_t n = read(fd, buf + have, sizeof buf - have);
            if (n <= 0) break;  // writer closed (or error): reopen
            have += (size_t)n;
            size_t whole = have / FRAME_BYTES;
            if (whole && a.stream) {
                aaudio_result_t w = AAudioStream_write(a.stream, buf, (int32_t)whole, 1000000000L);
                if (w < 0) {
                    LOGE("AAudio write: %s; reopening stream", AAudio_convertResultToText(w));
                    AAudioStream_close(a.stream);
                    a.stream = open_stream();
                } else {
                    atomic_fetch_add(&a.frames, w);
                }
            }
            size_t used = whole * FRAME_BYTES;
            memmove(buf, buf + used, have - used);
            have -= used;
            if (!atomic_load(&a.running)) break;
        }
        close(fd);
    }
    return NULL;
}

JNIEXPORT jstring JNICALL
Java_ro_cobrabm_fexdroid_AudioBridge_start(JNIEnv *env, jclass cls, jstring fifo) {
    (void)cls;
    if (atomic_load(&a.running)) return (*env)->NewStringUTF(env, "audio bridge already running");
    const char *f = (*env)->GetStringUTFChars(env, fifo, NULL);
    snprintf(a.fifo, sizeof a.fifo, "%s", f);
    (*env)->ReleaseStringUTFChars(env, fifo, f);
    a.stream = open_stream();
    if (!a.stream) return (*env)->NewStringUTF(env, "AAudio stream could not be opened");
    atomic_store(&a.running, true);
    pthread_create(&a.thread, NULL, pump, NULL);
    char msg[600];
    snprintf(msg, sizeof msg, "audio bridge: %s -> AAudio %d Hz", a.fifo, AAudioStream_getSampleRate(a.stream));
    return (*env)->NewStringUTF(env, msg);
}

JNIEXPORT jlong JNICALL
Java_ro_cobrabm_fexdroid_AudioBridge_frames(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return atomic_load(&a.frames);
}
