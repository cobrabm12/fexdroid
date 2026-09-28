package ro.cobrabm.fexdroid

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.concurrent.thread

/**
 * Memory, the resource a big game runs out of first on a phone (NOTES N-042, N-044).
 *
 * Dota 2 under the emulator holds 4.5 GB of ordinary memory at its main menu and 5 to 6 GB
 * in a match, plus 3 to 4 GB of video memory, which on a phone is the same memory. Next to Steam
 * and Android that is more than a 12 GB phone has: what does not fit is compressed (swap).
 * When that is used up too, the game drops to a frame every few seconds and Android ends
 * the session.
 */
object MemoryWatch {
    private const val PERIOD_MS = 3000L
    /** MemAvailable under this for [PERIODS] samples in a row: Android is about to kill. */
    private const val LOW_KB = 500L * 1024
    /** Swap free under this: what does not fit in memory has nowhere to go. */
    private const val LOW_SWAP_KB = 600L * 1024
    private const val PERIODS = 3

    @Volatile private var generation = 0

    /** True while memory is about to run out. Compose state: the player shows a warning. */
    var low by mutableStateOf(false); private set
    /** The last sample, for reports. */
    @Volatile var last = ""; private set

    private fun meminfo(): Map<String, Long> = File("/proc/meminfo").runCatching {
        readLines().mapNotNull { l ->
            val f = l.split(Regex("[:\\s]+"))
            f.getOrNull(1)?.toLongOrNull()?.let { f[0] to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    /**
     * The share of the phone's memory Turnip reports to games as video memory (Mesa's option
     * heap_memory_percent); Mesa's own choice is 75%. Dota 2 sizes its textures by it, but most
     * of what it holds does not depend on it: in a match its video memory grows for a quarter
     * of an hour, to 4.1 GB of a reported 4.6 GB and to 4.3 GB (still growing) of 8.6 GB. With
     * 2.4 GB it ends with "out of GPU video memory" while a match loads (NOTES N-044).
     */
    fun videoMemoryShare(): String? {
        val total = meminfo()["MemTotal"] ?: return null
        val share = maxOf(0.40, 4.0 * 1024 * 1024 / total).coerceAtMost(0.75)
        return "%.2f".format(java.util.Locale.ROOT, share)
    }

    fun start(log: (String) -> Unit) {
        val mine = ++generation
        thread(name = "memory-watch", isDaemon = true) {
            var under = 0
            var told = false
            try {
                while (generation == mine) {
                    Thread.sleep(PERIOD_MS)
                    val m = meminfo()
                    val available = m["MemAvailable"] ?: continue
                    val swapTotal = m["SwapTotal"] ?: 0
                    val swapFree = m["SwapFree"] ?: 0
                    last = "${available shr 10} MB available, ${swapFree shr 10} of ${swapTotal shr 10} MB swap free" +
                        (m["GPUTotalUsed"]?.let { ", ${it shr 10} MB video memory in use" } ?: "")
                    // A phone without swap has only the first sign.
                    val tight = available < LOW_KB || (swapTotal > 0 && swapFree < LOW_SWAP_KB)
                    under = if (tight) under + 1 else 0
                    low = under >= PERIODS
                    if (low && !told) { told = true; log("Memory is running out: $last") }
                    if (under == 0) told = false
                }
            } catch (_: InterruptedException) {
            } finally {
                if (generation == mine) low = false
            }
        }
    }

    fun stop() { generation++; low = false }
}
