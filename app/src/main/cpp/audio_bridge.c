// Phase 4 audio: PulseAudio in the rootfs renders into a FIFO (module-pipe-sink,
// s16le, 48 kHz, stereo); this thread reads the FIFO and plays it with AAudio.
//
// Buffers: the output stream holds BUFFER_MS of sound. With the low latency mode and its
// buffer of one or two bursts (2-4 ms) every delay of this thread was a gap in the sound, and
// on a loaded phone there were many. The FIFO is cut from the kernel's 64 KB (340 ms of
// sound, always full) to FIFO_BYTES, so the sound is not later than before.
#define _GNU_SOURCE
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
#define BUFFER_MS 80
#define FIFO_BYTES 16384 // 85 ms

static struct {
    char fifo[512];
    pthread_t thread;
    atomic_bool running;
    atomic_long frames;
    atomic_int xruns; // Gaps in the output since the stream was opened.
    atomic_int holes; // Short silences inside the sound that arrived (see count_holes).
    AAudioStream *stream;
} a;

// PulseAudio fills in silence when a program does not deliver its sound in time. Such a hole
// is a run of zero samples, 3 to 150 ms long, with sound right before and right after it;
// music and effects do not contain exact zeros for that long. Counted here so that a change
// (buffer sizes, the game's own settings) can be judged without listening.
static void count_holes(const int16_t *pcm, size_t frames) {
    static size_t zeros;       // Length of the current run of silent frames.
    static bool sound_before;  // There was sound before that run.
    for (size_t i = 0; i < frames; i++) {
        if (pcm[2 * i] == 0 && pcm[2 * i + 1] == 0) {
            zeros++;
            continue;
        }
        if (sound_before && zeros >= RATE * 3 / 1000 && zeros <= RATE * 150 / 1000) atomic_fetch_add(&a.holes, 1);
        zeros = 0;
        sound_before = true;
    }
    if (zeros > RATE * 150 / 1000) sound_before = false; // A pause, not a hole.
}

static AAudioStream *open_stream(void) {
    AAudioStreamBuilder *b = NULL;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return NULL;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(b, CHANNELS);
    AAudioStreamBuilder_setSampleRate(b, RATE);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_NONE);
    AAudioStreamBuilder_setBufferCapacityInFrames(b, RATE * BUFFER_MS * 2 / 1000);
    AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_GAME);
    AAudioStream *s = NULL;
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &s);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) { LOGE("openStream: %s", AAudio_convertResultToText(r)); return NULL; }
    AAudioStream_setBufferSizeInFrames(s, RATE * BUFFER_MS / 1000);
    AAudioStream_requestStart(s);
    LOGI("AAudio stream: %d Hz, burst %d frames, buffer %d of %d frames", AAudioStream_getSampleRate(s),
         AAudioStream_getFramesPerBurst(s), AAudioStream_getBufferSizeInFrames(s), AAudioStream_getBufferCapacityInFrames(s));
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
        int pipe_bytes = fcntl(fd, F_SETPIPE_SZ, FIFO_BYTES);
        LOGI("FIFO opened (%d bytes), streaming", pipe_bytes);
        long reported = atomic_load(&a.frames);
        for (;;) {
            ssize_t n = read(fd, buf + have, sizeof buf - have);
            if (n <= 0) break;  // writer closed (or error): reopen
            have += (size_t)n;
            size_t whole = have / FRAME_BYTES;
            if (whole) count_holes((const int16_t *)buf, whole);
            if (whole && a.stream) {
                aaudio_result_t w = AAudioStream_write(a.stream, buf, (int32_t)whole, 1000000000L);
                if (w < 0) {
                    LOGE("AAudio write: %s; reopening stream", AAudio_convertResultToText(w));
                    AAudioStream_close(a.stream);
                    a.stream = open_stream();
                } else {
                    long total = atomic_fetch_add(&a.frames, w) + w;
                    atomic_store(&a.xruns, AAudioStream_getXRunCount(a.stream));
                    if (total - reported >= 30L * RATE) { // Every 30 s of sound.
                        LOGI("%ld s played, %d output gaps, %d holes in the sound so far", total / RATE,
                             atomic_load(&a.xruns), atomic_load(&a.holes));
                        reported = total;
                    }
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

JNIEXPORT jint JNICALL
Java_ro_cobrabm_fexdroid_AudioBridge_gaps(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return atomic_load(&a.xruns);
}

JNIEXPORT jint JNICALL
Java_ro_cobrabm_fexdroid_AudioBridge_holes(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return atomic_load(&a.holes);
}
