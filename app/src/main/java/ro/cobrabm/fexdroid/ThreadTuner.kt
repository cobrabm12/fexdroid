package ro.cobrabm.fexdroid

import android.os.Process
import java.io.File
import kotlin.concurrent.thread

/** JNI bindings to cpp/sched.c. */
object Sched {
    init { System.loadLibrary("fxio") }

    /** 0 or errno. */
    @JvmStatic external fun setAffinity(tid: Int, mask: Long): Int
    /** The mask, or minus errno. */
    @JvmStatic external fun getAffinity(tid: Int): Long
}

/**
 * Puts the session's threads on the cores that suit them.
 *
 * A game under the emulator is limited by one thread (NOTES N-036), on a phone that lowers
 * its cores' frequency when hot. The kernel moves that thread between cores, at times to a
 * small one; the game's worker threads, which it waits for, run on small cores too; and the
 * rest of the session (Steam, the X server, sound) shares the fast cores with the game.
 *
 * Here the busiest thread of the busiest process gets the fastest core for itself, the other
 * threads of that process the other big cores, everything else the small cores.
 *
 * `files/tuner.txt` chooses while a session runs (for measurements): "off", "pin" (only the
 * busiest thread is placed), "big" (the default), or "masks A B C": the cores, as hexadecimal
 * masks, of the busiest thread, of the other threads of its process and of everything else.
 */
object ThreadTuner {
    private const val PERIOD_MS = 2000L
    /** Of one core, over a period: below it no thread is worth a core of its own. */
    private const val HEAVY = 0.40
    /** Periods a thread has to be the busiest one for before it gets (or loses) the core. */
    private const val STABLE = 3
    private const val EVERYTHING_EVERY = 5 // periods

    @Volatile private var generation = 0
    @Volatile var status = ""; private set

    /** Masks: [fast] one core of the fastest kind, [big] every core that is not a small one. */
    private class Cores(val all: Long, val fast: Long, val big: Long) {
        val small get() = (all and big.inv()).takeIf { it != 0L } ?: (all and fast.inv())
    }

    /** Null: the cores are all alike. */
    private fun cores(): Cores? {
        val max = HashMap<Int, Long>()
        File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }?.forEach { d ->
            val f = File(d, "cpufreq/cpuinfo_max_freq").runCatching { readText().trim().toLong() }.getOrNull() ?: return@forEach
            max[d.name.drop(3).toInt()] = f
        }
        if (max.size < 2 || max.keys.any { it > 62 }) return null
        val top = max.values.max()
        if (max.values.all { it == top }) return null
        fun mask(cpus: Collection<Int>) = cpus.fold(0L) { m, c -> m or (1L shl c) }
        // Small cores have well under the fastest one's frequency (Snapdragon 888: 1.8 of 2.84 GHz;
        // the performance cores of a Snapdragon 8 Elite, 3.5 of 4.3 GHz, are not small).
        return Cores(mask(max.keys), 1L shl max.filterValues { it == top }.keys.max(),
            mask(max.filterValues { it >= top * 7 / 10 }.keys))
    }

    fun start(files: File) {
        val mine = ++generation
        val cores = cores() ?: return
        thread(name = "thread-tuner", isDaemon = true) {
            val pid = Process.myPid()
            val uid = Process.myUid()
            var last = HashMap<Int, Long>() // tid -> ticks, of the busiest process
            var lastPid = -1
            var lastProcess = HashMap<Int, Long>()
            var owner = -1
            var candidate = -1
            var seen = 0
            var period = 0
            var active = false
            var mode = ""
            try {
                while (generation == mine) {
                    Thread.sleep(PERIOD_MS)
                    val wantedMode = File(files, "tuner.txt").runCatching { readText().trim() }.getOrNull()
                        ?.takeIf { it in setOf("off", "pin", "big") || masks(it, cores) != null } ?: "big"
                    if (wantedMode != mode) {
                        if (active) { place(uid, -1, -1, cores, "off"); active = false }
                        mode = wantedMode; owner = -1; candidate = -1; seen = 0
                    }
                    if (mode == "off") { status = "off"; continue }
                    // The busiest process of the session, then its busiest thread.
                    val process = HashMap<Int, Long>()
                    for (p in pids(uid)) if (p != pid) ticks(File("/proc/$p/stat"))?.let { process[p] = it }
                    val busiest = process.maxByOrNull { (p, t) -> lastProcess[p]?.let { t - it } ?: 0L }?.key
                    lastProcess = process
                    if (busiest == null) continue
                    val now = HashMap<Int, Long>()
                    File("/proc/$busiest/task").list()?.forEach { t ->
                        val tid = t.toIntOrNull() ?: return@forEach
                        ticks(File("/proc/$busiest/task/$t/stat"))?.let { now[tid] = it }
                    }
                    val top = if (busiest == lastPid) now.maxByOrNull { (t, v) -> last[t]?.let { v - it } ?: 0L } else null
                    val share = top?.let { (t, v) -> (v - (last[t] ?: v)) * 10.0 / PERIOD_MS } ?: 0.0 // ticks are 10 ms
                    last = now; lastPid = busiest
                    val wanted = if (top != null && share >= HEAVY) top.key else -1
                    if (wanted == candidate) seen++ else { candidate = wanted; seen = 1 }
                    if (candidate != owner && seen >= STABLE) {
                        owner = candidate
                        place(uid, if (owner < 0) -1 else busiest, owner, cores, if (owner < 0) "off" else mode)
                        active = owner >= 0
                    } else if (active && ++period % EVERYTHING_EVERY == 0) {
                        place(uid, busiest, owner, cores, mode) // Threads started since.
                    }
                    status = if (owner < 0) "no busy thread" else
                        "$mode: thread $owner (${name(owner)}) alone on core " +
                            "${java.lang.Long.numberOfTrailingZeros(cores.fast)}, ${(share * 100).toInt()}%"
                }
            } catch (_: InterruptedException) {
            } finally {
                if (active) place(uid, -1, -1, cores, "off")
                status = ""
            }
        }
    }

    fun stop() { generation++ }

    private fun pids(uid: Int): List<Int> = File("/proc").list()?.mapNotNull { it.toIntOrNull() }
        ?.filter { File("/proc/$it").runCatching { android.system.Os.stat(path).st_uid == uid }.getOrDefault(false) }.orEmpty()

    /** utime + stime of /proc/.../stat. */
    private fun ticks(stat: File): Long? = stat.runCatching {
        val s = readText()
        val f = s.substring(s.lastIndexOf(')') + 2).split(' ')
        f[11].toLong() + f[12].toLong()
    }.getOrNull()

    private fun name(tid: Int) = File("/proc/$tid/comm").runCatching { readText().trim() }.getOrDefault("?")

    /** "masks A B C" as three masks of existing cores, or null. */
    private fun masks(mode: String, cores: Cores): List<Long>? {
        val f = mode.split(' ')
        if (f.size != 4 || f[0] != "masks") return null
        return f.drop(1).map { (it.toLongOrNull(16) ?: return null) and cores.all }.takeIf { m -> m.all { it != 0L } }
    }

    /** Every thread of the app and the session where [mode] wants it. */
    private fun place(uid: Int, game: Int, owner: Int, cores: Cores, mode: String) {
        val (ownerMask, gameMask, otherMask) = when (mode) {
            "off" -> listOf(cores.all, cores.all, cores.all)
            "pin" -> (cores.all and cores.fast.inv()).let { listOf(cores.fast, it, it) }
            "big" -> listOf(cores.fast, cores.big and cores.fast.inv(), cores.small)
            else -> masks(mode, cores) ?: return
        }
        for (p in pids(uid)) {
            val mask = if (p == game) gameMask else otherMask
            File("/proc/$p/task").list()?.forEach { t ->
                val tid = t.toIntOrNull() ?: return@forEach
                Sched.setAffinity(tid, if (tid == owner) ownerMask else mask)
            }
        }
    }
}
