package ro.cobrabm.fexdroid

/** JNI bindings to app/src/main/cpp (libfxrecon.so). Each call returns a text block. */
object ReconNative {
    init { System.loadLibrary("fxrecon") }

    @JvmStatic external fun syscalls(): String
    @JvmStatic external fun wx(dataDir: String, nativeLibDir: String): String
    @JvmStatic external fun kgsl(): String
    @JvmStatic external fun vulkan(): String
    @JvmStatic external fun va(): String
}
