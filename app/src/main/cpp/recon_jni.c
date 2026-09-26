// JNI entry points for ReconNative.kt.
#include "probes.h"

#include <jni.h>
#include <stdlib.h>

typedef void (*simple_probe)(strbuf *);

static jstring finish(JNIEnv *env, strbuf *sb) {
    jstring s = (*env)->NewStringUTF(env, sb->data ? sb->data : "");
    free(sb->data);
    return s;
}

static jstring run_simple(JNIEnv *env, simple_probe fn) {
    strbuf sb = {0};
    fn(&sb);
    return finish(env, &sb);
}

JNIEXPORT jstring JNICALL
Java_ro_cobrabm_fexdroid_ReconNative_syscalls(JNIEnv *env, jclass cls) {
    (void)cls;
    return run_simple(env, probe_syscalls);
}

JNIEXPORT jstring JNICALL
Java_ro_cobrabm_fexdroid_ReconNative_kgsl(JNIEnv *env, jclass cls) {
    (void)cls;
    return run_simple(env, probe_kgsl);
}

JNIEXPORT jstring JNICALL
Java_ro_cobrabm_fexdroid_ReconNative_vulkan(JNIEnv *env, jclass cls) {
    (void)cls;
    return run_simple(env, probe_vulkan);
}

JNIEXPORT jstring JNICALL
Java_ro_cobrabm_fexdroid_ReconNative_wx(JNIEnv *env, jclass cls, jstring dataDir, jstring nativeLibDir) {
    (void)cls;
    const char *d = (*env)->GetStringUTFChars(env, dataDir, NULL);
    const char *n = (*env)->GetStringUTFChars(env, nativeLibDir, NULL);
    strbuf sb = {0};
    probe_wx(&sb, d, n);
    (*env)->ReleaseStringUTFChars(env, dataDir, d);
    (*env)->ReleaseStringUTFChars(env, nativeLibDir, n);
    return finish(env, &sb);
}

JNIEXPORT jstring JNICALL
Java_ro_cobrabm_fexdroid_ReconNative_va(JNIEnv *env, jclass cls) {
    (void)cls;
    return run_simple(env, probe_va);
}
