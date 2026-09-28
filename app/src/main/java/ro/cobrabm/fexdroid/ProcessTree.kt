package ro.cobrabm.fexdroid

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Stops a started program together with everything it spawned. Process.destroy() only
 * signals the direct child (usually /bin/sh); FEX, Steam, steamwebhelper and the game
 * below it would keep running and hold CPU, RAM and the X display.
 * Everything here runs as the app's uid, so /proc of these processes is readable.
 */
object ProcessTree {
    private val pidRegex = Regex("pid=(\\d+)")

    /** PID of a process started with ProcessBuilder (Android's UNIXProcess), or -1. */
    fun pidOf(p: Process): Int {
        pidRegex.find(p.toString())?.let { return it.groupValues[1].toInt() }
        return runCatching {
            p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p)
        }.getOrDefault(-1)
    }

    /** pid -> ppid for every process we can see (other uids are hidden or unreadable). */
    private fun parents(): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        File("/proc").list()?.forEach { name ->
            val pid = name.toIntOrNull() ?: return@forEach
            val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return@forEach
            // "pid (comm) state ppid ...": comm may contain spaces/parens, so split after the last ')'.
            val rest = stat.substringAfterLast(')').trim().split(' ')
            rest.getOrNull(1)?.toIntOrNull()?.let { out[pid] = it }
        }
        return out
    }

    /** [root] and all its descendants, parents before children. */
    fun tree(root: Int): List<Int> {
        val children = parents().entries.groupBy({ it.value }, { it.key })
        val out = mutableListOf<Int>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            if (pid in out) continue
            out += pid
            children[pid]?.let { queue.addAll(it) }
        }
        return out
    }

    /** Still running (a zombie only waits to be reaped by its parent). */
    private fun alive(pid: Int): Boolean {
        val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return false
        return !stat.substringAfterLast(')').trimStart().startsWith("Z")
    }

    /**
     * Processes already re-parented to init whose command line mentions [dir] (programs
     * from our rootfs whose parent exited earlier, e.g. daemonized Steam helpers),
     * except the ones whose command line contains one of [keep].
     */
    fun orphansUnder(dir: String, keep: List<String> = emptyList()): List<Int> =
        parents().filter { (pid, ppid) -> ppid == 1 && pid != Os.getpid() }.keys.filter { pid ->
            val cmd = runCatching { File("/proc/$pid/cmdline").readText().replace('\u0000', ' ') }.getOrDefault("")
            dir in cmd && keep.none { it in cmd }
        }

    private fun signal(pid: Int, sig: Int) {
        if (pid <= 1 || pid == Os.getpid()) return
        runCatching { Os.kill(pid, sig) }
    }

    /**
     * SIGTERM to every process in the trees of [roots], then SIGKILL to what is still
     * alive after [graceMs]. Blocks for at most [graceMs] plus a moment: call off the UI thread.
     */
    fun killTrees(
        roots: List<Process>, extra: List<Int> = emptyList(), graceMs: Long = 2000, log: (String) -> Unit = {},
    ) {
        val pids = (roots.flatMap { p -> pidOf(p).takeIf { it > 0 }?.let { tree(it) } ?: emptyList() } +
            extra.flatMap { tree(it) }).distinct()
        pids.forEach { signal(it, OsConstants.SIGTERM) }
        roots.forEach { it.destroy() }
        val deadline = SystemClock.elapsedRealtime() + graceMs
        while (pids.any(::alive) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        val left = pids.filter(::alive)
        if (left.isNotEmpty()) {
            log("Processes that did not stop in ${graceMs} ms are killed: ${left.joinToString()}")
            left.forEach { signal(it, OsConstants.SIGKILL) }
        }
        roots.forEach { runCatching { it.destroyForcibly() } }
    }
}
