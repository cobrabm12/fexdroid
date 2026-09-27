package ro.cobrabm.fexdroid

import java.io.File
import kotlin.concurrent.thread

/**
 * Phase 3: X server (Xvfb in the rootfs) shown in a SurfaceView, plus vkcube
 * natively (arm64 + Turnip) and through FEX (x86_64 + Vulkan thunk).
 */
class XSession(
    private val env: LinuxEnv,
    val width: Int = Resolution.DEFAULT.width,
    val height: Int = Resolution.DEFAULT.height,
    private val log: (String) -> Unit,
) {
    @Volatile private var xvfb: Process? = null
    private val clients = mutableListOf<Process>()
    /** Guards [clients]; not the instance monitor, which startX() holds while it waits for Xvfb. */
    private val procLock = Any()
    @Volatile var shmid: Int = -1; private set

    val fxshmSocket get() = "${env.root}/tmp/.fxshm/sock"
    private val preload get() = "${env.root}/usr/lib/fexdroid/libfxpath.so"

    val pulseSocket get() = "${env.root}/tmp/pulse/native"
    private val audioFifo get() = "${env.root}/tmp/pa.fifo"
    @Volatile private var pulse: Process? = null

    fun clientEnv(): Map<String, String> = env.environment() + mapOf(
        "DISPLAY" to ":0",
        "PULSE_SERVER" to "unix:$pulseSocket",
        // Turnip on KGSL has no DRI3 path to Xvfb: present through the CPU (MIT-SHM).
        "MESA_VK_WSI_DEBUG" to "sw",
    )

    /** Starts Xvfb and blocks until it reports its framebuffer shmid (or fails). */
    @Synchronized
    fun startX(): Boolean {
        if (xvfb?.isAlive == true && shmid >= 0) return true
        shmid = -1
        File(env.root, "tmp/.X0-lock").delete()
        val pb = ProcessBuilder(
            "${env.root}/usr/bin/Xvfb", ":0", "-screen", "0", "${width}x${height}x24",
            "-shmem", "-ac", "-nolisten", "tcp", "-pn",
        ).redirectErrorStream(true)
        pb.environment().apply {
            clear(); putAll(env.environment()); put("LD_PRELOAD", preload)
            // GLX: Xvfb dlopen()s swrast from its built-in /usr path, which ld.so opens
            // directly (no fxpath). Only for Xvfb: x86 clients must keep their own Mesa.
            put("LIBGL_DRIVERS_PATH", "${env.root}/usr/lib/aarch64-linux-gnu/dri")
        }
        val p = pb.start()
        xvfb = p
        val ready = Object()
        thread(name = "xvfb-log", isDaemon = true) {
            p.forEachOutputLine { line ->
                log("[Xvfb] $line")
                Regex("screen 0 shmid (\\d+)").find(line)?.let {
                    shmid = it.groupValues[1].toInt()
                    synchronized(ready) { ready.notifyAll() }
                }
            }
            synchronized(ready) { ready.notifyAll() }
        }
        synchronized(ready) { if (shmid < 0 && p.isAlive) ready.wait(20_000) }
        return shmid >= 0
    }

    /**
     * Phase 4 audio: PulseAudio (rootfs) -> module-pipe-sink FIFO -> AudioBridge -> AAudio.
     * Modules are dlopen()ed from a compiled-in /usr path, hence --dl-search-path.
     */
    @Synchronized
    fun startAudio(): Boolean {
        if (pulse?.isAlive == true) return true
        val fifo = File(audioFifo)
        if (!fifo.exists()) android.system.Os.mkfifo(fifo.path, 384 /* 0600 */)
        log(AudioBridge.start(fifo.path))
        val modules = File(env.root, "usr/lib").listFiles { f -> f.name.startsWith("pulse-") }
            ?.firstOrNull()?.let { File(it, "modules") }
            ?: run { log("PulseAudio modules not found in rootfs"); return false }
        File(env.root, "tmp/pulse").mkdirs()
        val pb = ProcessBuilder(
            "${env.root}/usr/bin/pulseaudio", "-n", "--daemonize=no", "--exit-idle-time=-1",
            "--use-pid-file=no", "--disable-shm=yes", "--dl-search-path=${modules.path}", "--log-target=stderr",
            "-L", "module-pipe-sink file=$audioFifo sink_name=android format=s16le rate=48000 channels=2",
            "-L", "module-native-protocol-unix auth-anonymous=1 socket=$pulseSocket",
        ).redirectErrorStream(true)
        pb.environment().apply {
            clear(); putAll(env.environment()); put("LD_PRELOAD", preload)
            // GLX: Xvfb dlopen()s swrast from its built-in /usr path, which ld.so opens
            // directly (no fxpath). Only for Xvfb: x86 clients must keep their own Mesa.
            put("LIBGL_DRIVERS_PATH", "${env.root}/usr/lib/aarch64-linux-gnu/dri")
        }
        val p = pb.start()
        pulse = p
        thread(name = "pulse-log", isDaemon = true) {
            p.forEachOutputLine { log("[pulse] $it") }
            log("[pulse] exit ${p.waitFor()}")
        }
        for (i in 0 until 50) { if (File(pulseSocket).exists()) return true; Thread.sleep(100) }
        return File(pulseSocket).exists()
    }

    /** Starts a client on this display; its output goes to the log. Returns the process. */
    fun launch(title: String, argv: List<String>): Process {
        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        pb.environment().apply { clear(); putAll(clientEnv()) }
        val p = pb.start()
        synchronized(procLock) { clients += p }
        thread(name = title, isDaemon = true) {
            p.forEachOutputLine { log("[$title] $it") }
            log("[$title] exit ${p.waitFor()}")
        }
        return p
    }

    val running get() = xvfb?.isAlive == true

    /**
     * Stops the clients with everything they spawned (FEX, Steam, the game...), then
     * PulseAudio and Xvfb. Blocks up to ~2 s: call off the UI thread. fxshmd stays up
     * (it is shared with other sessions and restarts on demand anyway).
     */
    fun stopAll() {
        val procs = synchronized(procLock) {
            val all = clients.toList() + listOfNotNull(pulse, xvfb)
            clients.clear()
            xvfb = null
            pulse = null
            shmid = -1
            all
        }
        val orphans = ProcessTree.orphansUnder(env.files.path, keep = listOf("fxshmd"))
        ProcessTree.killTrees(procs, orphans, log = log)
    }
}

/**
 * Calls [onLine] for every line the process prints, until it closes its output. Stopping the
 * process closes the stream under the reader (InterruptedIOException "read interrupted"): that
 * is the end of the output, not an error, and must not take the app down with it.
 */
fun Process.forEachOutputLine(onLine: (String) -> Unit) {
    try {
        inputStream.bufferedReader().forEachLine(onLine)
    } catch (_: java.io.IOException) {
    }
}
