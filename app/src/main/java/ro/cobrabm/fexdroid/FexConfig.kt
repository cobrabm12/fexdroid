package ro.cobrabm.fexdroid

import org.json.JSONObject
import java.io.File

/**
 * FEX speed/accuracy trade-offs the user picks in Setări › Performanță.
 * Option names and defaults are from FEX-2609 FEXCore/Source/Interface/Config/Config.json.in.
 */
enum class FexProfile(val label: String, val description: String, val options: Map<String, String>) {
    /** Strictest x86 memory ordering: for games that crash or glitch with the default. */
    COMPATIBLE("Compatibil",
        "Ordine a memoriei x86 completă, și pentru vectori și memcpy. Cel mai lent; pentru jocuri care crapă.",
        mapOf("TSOEnabled" to "1", "VectorTSOEnabled" to "1", "MemcpySetTSOEnabled" to "1",
            "HalfBarrierTSOEnabled" to "1", "X87ReducedPrecision" to "0")),
    /** FEX's own defaults. */
    BALANCED("Echilibrat",
        "Setările implicite FEX. Recomandat.",
        mapOf("TSOEnabled" to "1", "VectorTSOEnabled" to "0", "MemcpySetTSOEnabled" to "0",
            "HalfBarrierTSOEnabled" to "1", "X87ReducedPrecision" to "0")),
    /** No TSO emulation: much faster, but multithreaded games may crash or hang. */
    FAST("Rapid",
        "Fără emularea ordinii memoriei x86 și cu x87 pe 64 de biți. Mult mai rapid, dar unele jocuri pot crăpa sau îngheța.",
        mapOf("TSOEnabled" to "0", "VectorTSOEnabled" to "0", "MemcpySetTSOEnabled" to "0",
            "HalfBarrierTSOEnabled" to "0", "X87ReducedPrecision" to "1")),
}

object FexConfig {
    /** FEX_APP_CONFIG_LOCATION (LinuxEnv): FEX's per-user layer over the global Config.json. */
    fun configDir(env: LinuxEnv) = File(env.home, ".fex-emu")
    /** FEX_APP_CACHE_LOCATION (LinuxEnv): JIT disk cache, kept across payload updates. */
    fun cacheDir(env: LinuxEnv) = File(env.home, ".cache/fex-emu")

    /**
     * Writes the per-user Config.json from the app settings before a session starts.
     * The global file (scripts/build-fex.sh) keeps RootFS and thunk paths; this layer only
     * sets the options below, so the file is owned by the app and rewritten every time.
     * FEX reads every value as a string.
     */
    fun write(env: LinuxEnv, profile: FexProfile, diskCache: Boolean) {
        val config = JSONObject()
        for ((k, v) in profile.options) config.put(k, v)
        config.put("Multiblock", "1")
        // Code blocks compiled once are stored under the cache dir and reused by later
        // runs (FEX-2609 CPU.DiskCache): shorter loading after the first start.
        config.put("DiskCache", if (diskCache) "1" else "0")
        val root = JSONObject().put("Config", config)
        val dir = configDir(env).apply { mkdirs() }
        cacheDir(env).mkdirs()
        val tmp = File(dir, "Config.json.tmp")
        tmp.writeText(root.toString(2) + "\n")
        tmp.renameTo(File(dir, "Config.json"))
    }

    /** Size of the JIT disk cache in bytes (Setări). Does file I/O. */
    fun cacheSize(env: LinuxEnv): Long = cacheDir(env).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun clearCache(env: LinuxEnv) {
        cacheDir(env).deleteRecursively()
    }
}
