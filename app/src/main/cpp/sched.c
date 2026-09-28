// Which cores a thread of the session may run on (ThreadTuner.kt). The session's processes
// are the app's children: same user, same SELinux domain, so the app may set this for them.
#define _GNU_SOURCE
#include <errno.h>
#include <jni.h>
#include <sched.h>
#include <sys/resource.h>

JNIEXPORT jint JNICALL
Java_ro_cobrabm_fexdroid_Sched_setAffinity(JNIEnv *env, jclass cls, jint tid, jlong mask) {
    (void)env; (void)cls;
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int i = 0; i < 64; i++)
        if ((mask >> i) & 1) CPU_SET(i, &set);
    return sched_setaffinity(tid, sizeof set, &set) == 0 ? 0 : errno;
}

/** The mask, or minus errno. */
JNIEXPORT jlong JNICALL
Java_ro_cobrabm_fexdroid_Sched_getAffinity(JNIEnv *env, jclass cls, jint tid) {
    (void)env; (void)cls;
    cpu_set_t set;
    CPU_ZERO(&set);
    if (sched_getaffinity(tid, sizeof set, &set) != 0) return -(jlong)errno;
    jlong mask = 0;
    for (int i = 0; i < 63; i++)
        if (CPU_ISSET(i, &set)) mask |= 1LL << i;
    return mask;
}

// setpriority() for one thread: on Linux PRIO_PROCESS with a thread id means that thread.
JNIEXPORT jint JNICALL
Java_ro_cobrabm_fexdroid_Sched_setNice(JNIEnv *env, jclass cls, jint tid, jint nice) {
    (void)env; (void)cls;
    return setpriority(PRIO_PROCESS, (id_t)tid, nice) == 0 ? 0 : -errno;
}
