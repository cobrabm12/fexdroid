package ro.cobrabm.fexdroid

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Where a frame's time goes, measured on the phone while a game runs, for the report a
 * tester sends (NOTES N-031 did the same by hand over adb): CPU per process and per thread
 * of the busiest one, GPU load, frequency limits, temperatures, memory, frames shown.
 * Everything is read from /proc and /sys as the app's uid; what a phone does not let an
 * app read is reported as such.
 */
object PerfSnapshot {
    private const val SAMPLE_MS = 3000L

    private data class Proc(val pid: Int, val name: String, val ticks: Long, val rssKb: Long)
    private data class Task(val tid: Int, val name: String, val ticks: Long, val cpu: Int)

    private fun read(path: String): String? = runCatching { File(path).readText().trim() }.getOrNull()

    /** Fields of a /proc/.../stat line after "pid (comm)"; comm may hold spaces and parentheses. */
    private fun statFields(stat: String) = stat.substringAfterLast(')').trim().split(' ')

    private fun ticks(f: List<String>) = (f.getOrNull(11)?.toLongOrNull() ?: 0) + (f.getOrNull(12)?.toLongOrNull() ?: 0)

    private fun processes(dir: String): List<Proc> = File("/proc").list().orEmpty().mapNotNull { n ->
        val pid = n.toIntOrNull() ?: return@mapNotNull null
        val cmd = read("/proc/$pid/cmdline")?.replace('\u0000', ' ') ?: return@mapNotNull null
        if (dir !in cmd && pid != Os.getpid()) return@mapNotNull null
        val f = statFields(read("/proc/$pid/stat") ?: return@mapNotNull null)
        val rss = (f.getOrNull(21)?.toLongOrNull() ?: 0) * 4
        // The program itself, without the emulator in front and the directories.
        val words = cmd.split(' ').filter { it.isNotEmpty() }
        val prog = (if (words.firstOrNull()?.endsWith("/FEX") == true) words.getOrNull(1) else words.firstOrNull())
            ?.substringAfterLast('/') ?: "?"
        Proc(pid, if (pid == Os.getpid()) "fexdroid (app)" else prog, ticks(f), rss)
    }

    private fun threads(pid: Int): List<Task> = File("/proc/$pid/task").list().orEmpty().mapNotNull { n ->
        val tid = n.toIntOrNull() ?: return@mapNotNull null
        val stat = read("/proc/$pid/task/$tid/stat") ?: return@mapNotNull null
        val f = statFields(stat)
        Task(tid, stat.substringAfter('(').substringBeforeLast(')'), ticks(f), f.getOrNull(36)?.toIntOrNull() ?: -1)
    }

    private fun gpuBusy(): Pair<Long, Long>? = read("/sys/class/kgsl/kgsl-3d0/gpubusy")
        ?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    fun take(env: LinuxEnv, screen: String): String = buildString {
        appendLine("---- performance (${SAMPLE_MS / 1000} s sample) ----")
        val hz = Os.sysconf(OsConstants._SC_CLK_TCK).coerceAtLeast(1)
        val dir = "${env.files.parentFile?.name}/files" // <package>/files: under /data/data and /data/user/0 alike
        val p0 = processes(dir)
        val shown0 = DisplayBridge.changedFrames()
        val looked0 = DisplayBridge.frames()
        val gaps0 = AudioBridge.gaps()
        val holes0 = AudioBridge.holes()
        val t0 = System.nanoTime()
        // The busiest process so far is the game (or Steam, before a game runs).
        val main = p0.filter { it.pid != Os.getpid() }.maxByOrNull { it.rssKb }
        val th0 = main?.let { threads(it.pid) }.orEmpty()
        val gpu0 = gpuBusy()
        Thread.sleep(SAMPLE_MS)
        val gpu1 = gpuBusy()
        val seconds = (System.nanoTime() - t0) / 1e9
        val gaps = AudioBridge.gaps() - gaps0
        val p1 = processes(dir).associateBy { it.pid }
        val th1 = main?.let { threads(it.pid) }.orEmpty().associateBy { it.tid }
        fun percent(d: Long) = "%.0f%%".format(d * 100.0 / hz / seconds)

        appendLine("frames with new content: %.1f per second (the display looks %d times a second)"
            .format((DisplayBridge.changedFrames() - shown0) / seconds, AppSettings.fps))
        if (DisplayBridge.frames() == looked0) appendLine("(the display bridge is not running)")
        appendLine("sound: in the sample $gaps output gaps and ${AudioBridge.holes() - holes0} holes from late programs; " +
            "since the session started ${AudioBridge.gaps()} and ${AudioBridge.holes()}")

        appendLine("processes, % of one core:")
        p0.mapNotNull { a -> p1[a.pid]?.let { b -> Triple(a, b.ticks - a.ticks, b.rssKb) } }
            .sortedByDescending { it.second }.take(8)
            .forEach { (p, d, rss) -> appendLine("  %5s  %5d MB  %s".format(percent(d), rss / 1024, p.name)) }

        if (main != null) {
            appendLine("threads of ${main.name} (pid ${main.pid}), % of one core, last core:")
            th0.mapNotNull { a -> th1[a.tid]?.let { b -> Triple(a, b.ticks - a.ticks, b.cpu) } }
                .sortedByDescending { it.second }.take(12)
                .forEach { (t, d, cpu) -> appendLine("  %5s  cpu%d  %s".format(percent(d), cpu, t.name)) }
        }

        appendLine(if (gpu0 != null && gpu1 != null && gpu1.second > 0)
            "gpu: busy %.0f%% (kgsl gpubusy), %s, max clock %s".format(gpu1.first * 100.0 / gpu1.second,
                read("/sys/class/kgsl/kgsl-3d0/gpu_model") ?: "?", read("/sys/class/kgsl/kgsl-3d0/max_gpuclk") ?: "?")
            else "gpu: load not readable on this phone")

        appendLine("cpu clusters, kHz now / allowed / hardware maximum:")
        File("/sys/devices/system/cpu/cpufreq").list().orEmpty().filter { it.startsWith("policy") }.sorted().forEach { p ->
            val d = "/sys/devices/system/cpu/cpufreq/$p"
            appendLine("  cpus ${read("$d/related_cpus")}: ${read("$d/scaling_cur_freq")} / ${read("$d/scaling_max_freq")} / " +
                "${read("$d/cpuinfo_max_freq")} (${read("$d/scaling_governor")})")
        }

        // The hottest zone of each kind; names differ from phone to phone.
        val zones = File("/sys/class/thermal").list().orEmpty().filter { it.startsWith("thermal_zone") }.mapNotNull { z ->
            val type = read("/sys/class/thermal/$z/type") ?: return@mapNotNull null
            val t = read("/sys/class/thermal/$z/temp")?.toIntOrNull() ?: return@mapNotNull null
            type to (if (t > 1000) t / 1000.0 else t.toDouble())
        }
        if (zones.isEmpty()) appendLine("temperatures: not readable on this phone")
        else appendLine("temperatures, °C: " + listOf("cpu", "gpu", "skin", "shell", "sys-therm", "battery").mapNotNull { kind ->
            zones.filter { kind in it.first.lowercase() && it.second in 1.0..150.0 }.maxByOrNull { it.second }
                ?.let { "$kind %.0f (%s)".format(it.second, it.first) }
        }.joinToString(", "))

        read("/proc/meminfo")?.lines()?.filter { it.startsWith("MemAvailable") || it.startsWith("SwapFree") || it.startsWith("MemTotal") }
            ?.joinToString(", ") { it.replace(Regex("\\s+"), " ") }?.let { appendLine("memory: $it") }
        appendLine("settings: screen $screen, FEX profile ${AppSettings.fexProfile}")
        // A game whose window does not have the input focus slows itself down (N-034).
        val focus = ArrayList<String>()
        runCatching {
            val pb = ProcessBuilder("${env.root}/usr/bin/fxwmfit", "--focus-info").redirectErrorStream(true)
            pb.environment().apply {
                clear(); putAll(env.environment())
                put("DISPLAY", ":0"); put("LD_PRELOAD", "${env.root}/usr/lib/fexdroid/libfxpath.so")
            }
            val p = pb.start()
            p.outputStream.close()
            p.forEachOutputLine { focus += it }
            p.waitFor()
        }
        appendLine("x11: ${focus.joinToString("; ").ifEmpty { "focus not readable" }}")
    }
}
