package ro.cobrabm.fexdroid

import android.view.Surface

/** JNI bindings to cpp/display_bridge.c (libfxdisplay.so). */
object DisplayBridge {
    init { System.loadLibrary("fxdisplay") }

    /** Attaches Xvfb's framebuffer segment [shmid] via fxshmd at [sockPath] and starts copying to [surface]. */
    @JvmStatic external fun start(surface: Surface, sockPath: String, shmid: Int, fps: Int): String
    @JvmStatic external fun stop()
    @JvmStatic external fun frames(): Long
    /** Frames that differed from the one before: what the game draws, up to the copy rate. */
    @JvmStatic external fun changedFrames(): Long
}
