package ro.cobrabm.fexdroid

import android.view.Surface

/** JNI bindings to cpp/display_bridge.c (libfxdisplay.so). */
object DisplayBridge {
    init { System.loadLibrary("fxdisplay") }

    /**
     * Attaches Xvfb's framebuffer segment [shmid] via fxshmd at [sockPath] and starts copying to
     * [surface]. Games send their frames to the socket at [presentPath] ("": they cannot).
     */
    @JvmStatic external fun start(surface: Surface, sockPath: String, shmid: Int, fps: Int, presentPath: String): String
    /** Frames shown straight from a game since the bridge started. */
    @JvmStatic external fun directFrames(): Long
    @JvmStatic external fun stop()
    @JvmStatic external fun frames(): Long
    /** Frames that differed from the one before: what the game draws, up to the copy rate. */
    @JvmStatic external fun changedFrames(): Long
}
