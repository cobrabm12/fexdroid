package ro.cobrabm.fexdroid

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.concurrent.thread

/** What the user can start from the home screen (scripts from tools/steam, installed into the rootfs). */
enum class Game(val title: String, val tag: String, private val script: String) {
    STEAM("Steam", "steam", "fexdroid-steam.sh"),
    DOTA("Dota 2", "dota", "fexdroid-dota.sh");

    fun argv(env: LinuxEnv) = listOf("${env.root}/bin/sh", "${env.root}/usr/lib/fexdroid/steam/$script")
}

/** Startup steps shown in the progress panel, in order. */
enum class StartStep(val label: String) {
    PREPARE("Pregătesc mediul Linux"),
    DISPLAY("Pornesc ecranul virtual"),
    AUDIO("Pornesc sunetul"),
    LAUNCH("Pornesc jocul"),
}

sealed interface SessionState {
    data object Idle : SessionState
    data class Starting(val game: Game, val step: StartStep, val detail: String = "") : SessionState
    data class Running(val game: Game, val sinceMs: Long) : SessionState
    data class Exited(val game: Game, val code: Int) : SessionState
    data class Failed(val game: Game, val message: String) : SessionState
}

/** What is installed on the device (home screen status card). */
data class InstallStatus(
    val payloadReady: Boolean,
    val payloadProblem: String?,
    val steamRootfs: Boolean,
    val steamClient: Boolean,
    val dota: Boolean,
    val gameRootfs: Boolean,
) {
    companion object {
        /** Does file I/O: call off the main thread. */
        fun check(env: LinuxEnv) = InstallStatus(
            payloadReady = !env.needsInstall() && env.installedVersion() != null,
            payloadProblem = env.pathProblem(),
            steamRootfs = File(env.x86Steam, ".complete").exists(),
            steamClient = File(env.home, ".local/share/Steam/steam.sh").exists(),
            dota = File(env.steamLibrary, "steamapps/common/dota 2 beta/game/dota.sh").exists(),
            gameRootfs = GameRootfs.ready(env) && !GameRootfs.needsBuild(env),
        )
    }
}

/**
 * The user-facing game session (Acasă › Pornește …): one X display with audio and one
 * game client, plus the display bridge to whatever surface the player screen provides.
 * App-wide singleton so it survives navigation; the same code paths as DisplayScreen.
 */
object GameSession {
    var state by mutableStateOf<SessionState>(SessionState.Idle); private set
    /** False while the user is back in the menus with the game still running. */
    var playerVisible by mutableStateOf(false)
    /** The X input connection is up: InputSurfaceView may forward events. */
    var inputReady by mutableStateOf(false); private set
    var log by mutableStateOf(""); private set
    var resolution by mutableStateOf(Resolution.DEFAULT); private set

    val active get() = state !is SessionState.Idle

    private var session: XSession? = null
    private var appContext: Context? = null
    private var surface: Surface? = null
    private var bridgeOn = false
    private var generation = 0
    /** Background stop of the previous session; a new start waits for it (Xvfb :0, the FIFO). */
    @Volatile private var stopping: Thread? = null

    // Log: FEX and Steam print thousands of lines. Keep them in a bounded ring and publish
    // the text to Compose at most every LOG_PUBLISH_MS, instead of rebuilding a 40 KB
    // string and recomposing on every line (which cost CPU the game needs).
    private const val LOG_MAX_CHARS = 40_000
    private const val STEAM_LOG_TAIL = 25
    private const val REPORT_LOG_LINES = 40
    private const val SELF_TEST_SECONDS = 25L
    private const val SELF_TEST_LINES = 8
    private const val LOG_PUBLISH_MS = 250L
    private val logLines = ArrayDeque<String>()
    private var logChars = 0
    private var logPending = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val publishLog = Runnable {
        log = synchronized(logLines) {
            logPending = false
            logLines.joinToString("\n", postfix = if (logLines.isEmpty()) "" else "\n")
        }
    }

    /** Everything a developer needs from a tester after a failed start, as text. */
    fun report(ctx: Context): String = buildString {
        val env = LinuxEnv(ctx)
        val pkg = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        appendLine("fexdroid ${pkg?.versionName} (${pkg?.longVersionCode}), commit ${BuildConfig.GIT_SHA.take(7)}")
        appendLine("payload: installed ${env.installedVersion()}, in APK ${env.payloadVersion()}")
        val soc = if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else android.os.Build.HARDWARE
        appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
            "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), $soc")
        appendLine("screen: ${resolution.label}, state: $state")
        // Most useful first: messages get cut off on the way (chat apps, clipboards).
        if (state is SessionState.Running) append(runCatching { PerfSnapshot.take(env, resolution.label) }
            .getOrElse { "---- performance ----\nfailed: $it\n" })
        appendLine("---- session log (last $REPORT_LOG_LINES lines) ----")
        appendLine(synchronized(logLines) { logLines.toList() }.takeLast(REPORT_LOG_LINES).joinToString("\n"))
        // Steam's own logs: what its updater and client did (no passwords in them).
        val steamLogs = File(env.home, ".local/share/Steam/logs")
        for (name in listOf("content_log.txt", "bootstrap_log.txt", "console-linux.txt", "stderr.txt")) {
            val f = File(steamLogs, name)
            if (!f.isFile) continue
            appendLine("---- Steam logs/$name (last $STEAM_LOG_TAIL lines) ----")
            appendLine(runCatching { f.readLines().takeLast(STEAM_LOG_TAIL).joinToString("\n") }
                .getOrElse { "cannot read: $it" })
        }
        append(selfTests(env))
        appendLine("---- device checks ----")
        appendLine(runCatching { DeviceCheck.report(DeviceCheck.run(ctx)) }.getOrElse { "device checks failed: $it" })
    }

    private val reporting = java.util.concurrent.atomic.AtomicBoolean(false)

    /** One report at a time: two runs of the self-tests at once disturb each other. */
    fun reportOnce(ctx: Context): String? {
        if (!reporting.compareAndSet(false, true)) return null
        try { return report(ctx) } finally { reporting.set(false) }
    }

    /**
     * The smallest programs that show which layer fails on a phone nobody has debugged:
     * the arm64 Linux environment, the emulator alone, Vulkan natively and through the emulator.
     */
    private fun selfTests(env: LinuxEnv): String = buildString {
        appendLine("---- self-tests ----")
        if (env.pathProblem() != null || env.needsInstall()) {
            appendLine("skipped: the Linux environment is not installed")
            return@buildString
        }
        val fex = "${env.root}/usr/bin/FEX"
        val tests = "${env.x86Base}/opt/fexdroid-tests"
        // name, program, the x86 tree FEX runs it in (null: the one games use).
        val steps = listOf(
            Triple("arm64 sh", listOf("${env.root}/bin/sh", "-c", "uname -a"), null),
            // The test programs are in the base tree only; Steam's tree has the 32-bit libraries.
            Triple("FEX hello (x86_64)", listOf(fex, "$tests/hello-dynamic"), env.x86Base),
            Triple("FEX SysV semaphores (x86_64)", listOf(fex, "$tests/semtest", "all"), env.x86Base),
            Triple("FEX SysV semaphores (i386, as Steam's client)", listOf(fex, "$tests/semtest-i386", "all"), env.x86Steam),
            // Steam needs to create /tmp/dumps: fails where the phone has its own, read-only /tmp
            // and FEX does not send the guest's /tmp to the RootFS (Galaxy S26 Ultra, N-033).
            Triple("FEX: create and remove under /tmp (x86_64)", listOf("${env.root}/bin/sh", "-c",
                "PATH=/usr/bin:/bin exec '$fex' '${env.x86Root}/bin/sh' -c " +
                    "'mkdir /tmp/fxd-selftest && touch /tmp/fxd-selftest/a && rm -r /tmp/fxd-selftest && echo created and removed'"), null),
            Triple("host directories", listOf("/system/bin/sh", "-c",
                "for d in /tmp /var /run /usr /opt /etc; do /system/bin/ls -ld \$d 2>&1; done; exit 0"), null),
            Triple("vulkaninfo arm64", listOf("${env.root}/usr/bin/vulkaninfo", "--summary"), null),
            Triple("vulkaninfo x86_64 through FEX", listOf(fex, "${env.x86Root}/usr/bin/vulkaninfo", "--summary"), null),
        )
        for ((name, argv, tree) in steps) {
            if (tree != null && !tree.isDirectory) {
                appendLine("[$name] skipped: ${tree.name} is not installed")
                continue
            }
            val out = ArrayList<String>()
            val started = System.currentTimeMillis()
            val result = runCatching {
                val pb = ProcessBuilder(argv).redirectErrorStream(true).directory(env.home.apply { mkdirs() })
                pb.environment().apply {
                    clear(); putAll(env.environment())
                    if (tree != null) put("FEX_ROOTFS", tree.path)
                }
                val p = pb.start()
                p.outputStream.close()
                val reader = kotlin.concurrent.thread(name = "self-test") {
                    p.forEachOutputLine { if (!it.startsWith("Linking address ")) synchronized(out) { out += it } }
                }
                if (p.waitFor(SELF_TEST_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                    reader.join(1000)
                    describeExit(p.exitValue())
                } else {
                    ProcessTree.killTrees(listOf(p), emptyList(), log = {})
                    "no answer after $SELF_TEST_SECONDS s, stopped"
                }
            }.getOrElse { "cannot start: $it" }
            appendLine("[$name] $result, ${System.currentTimeMillis() - started} ms")
            val lines = synchronized(out) { out.toList() }
            val shown = if (name == "host directories") lines
                else if (result == "exit 0") lines.takeLast(1)
                else (lines.filter { "FAIL" in it } + lines.takeLast(SELF_TEST_LINES)).distinct()
            shown.forEach { appendLine("  $it") }
        }
    }

    /** Java reports a process killed by signal N as exit code 128 + N. */
    private fun describeExit(code: Int): String {
        val signals = mapOf(4 to "SIGILL", 6 to "SIGABRT", 7 to "SIGBUS", 9 to "SIGKILL", 11 to "SIGSEGV",
            15 to "SIGTERM", 31 to "SIGSYS")
        val sig = code - 128
        return if (sig in 1..64) "killed by signal $sig (${signals[sig] ?: "?"})" else "exit $code"
    }

    private fun clearLog() {
        synchronized(logLines) { logLines.clear(); logChars = 0 }
        log = ""
    }

    private fun append(line: String) {
        // FEX's Vulkan thunk prints one such line per function, hundreds per process: they
        // pushed everything useful out of the log.
        if ("] Linking address 0x" in line) return
        Log.i("fexdroid-game", line)
        synchronized(logLines) {
            logLines.addLast(line)
            logChars += line.length + 1
            while (logChars > LOG_MAX_CHARS && logLines.size > 1) logChars -= logLines.removeFirst().length + 1
            if (!logPending) { logPending = true; mainHandler.postDelayed(publishLog, LOG_PUBLISH_MS) }
        }
        val s = state
        if (s is SessionState.Starting && s.step == StartStep.PREPARE && line.isNotBlank())
            state = s.copy(detail = line.trim())
    }

    fun start(ctx: Context, game: Game) {
        if (active && state !is SessionState.Exited && state !is SessionState.Failed) { playerVisible = true; return }
        stop()
        appContext = ctx.applicationContext
        GameService.start(ctx.applicationContext, game.title)
        val env = LinuxEnv(ctx.applicationContext)
        // The caller's context (an Activity knows its display: phone screen, DeX monitor, ...).
        val res = AppSettings.sessionResolution(ctx)
        val gen = synchronized(this) { ++generation }
        val xs = XSession(env, res.width, res.height, ::append)
        session = xs
        resolution = res
        clearLog()
        inputReady = false
        state = SessionState.Starting(game, StartStep.PREPARE)
        playerVisible = true
        val previous = stopping
        thread(name = "game-start") {
            fun current() = synchronized(this) { gen == generation }
            fun step(s: StartStep) { if (current()) state = SessionState.Starting(game, s) }
            fun fail(msg: String) { append(msg); if (current()) state = SessionState.Failed(game, msg) }
            try {
                previous?.join()
                if (game == Game.DOTA && !File(env.steamLibrary, "steamapps/common/dota 2 beta/game/dota.sh").exists())
                    return@thread fail("Dota 2 nu este instalat pe telefon. Vezi Setări › Instalare jocuri.")
                if (!env.ensureInstalled(::append))
                    return@thread fail(env.pathProblem() ?: "Mediul Linux nu a putut fi instalat.")
                // Steam updated (or just downloaded) its runtime: the games' root filesystem follows.
                if (GameRootfs.needsBuild(env)) {
                    runCatching { GameRootfs.build(env, ::append) }.onFailure { append("Mediul jocului: eroare: $it") }
                }
                runCatching { DotaProfile.apply(env, AppSettings.dotaPerformance) }
                    .onSuccess { it?.let(::append) }.onFailure { append("Profilul Dota 2 nu a putut fi aplicat: $it") }
                runCatching { FexConfig.write(env, AppSettings.fexProfile, AppSettings.fexDiskCache, AppSettings.source2WithoutTso) }
                    .onSuccess {
                        append("FEX: profil ${AppSettings.fexProfile.label}, cache de cod ${if (AppSettings.fexDiskCache) "pornit" else "oprit"}" +
                            ", memorie rapidă Source 2 ${if (AppSettings.source2WithoutTso) "pornită" else "oprită"}" +
                            ", așezarea firelor ${if (AppSettings.threadPlacement) "pornită" else "oprită"}")
                    }
                    .onFailure { append("FEX: nu pot scrie Config.json: $it") }
                if (!current()) return@thread xs.stopAll()
                step(StartStep.DISPLAY)
                if (!xs.startX()) return@thread fail("Ecranul virtual (Xvfb) nu a pornit.")
                if (!current()) return@thread xs.stopAll()
                attachBridge()
                step(StartStep.AUDIO)
                if (!xs.startAudio()) append("Sunetul nu a pornit; continui fără sunet.")
                if (!current()) return@thread xs.stopAll()
                step(StartStep.LAUNCH)
                // Big Picture only once somebody has logged in: the first start (client download,
                // login) is verified in the desktop interface only.
                val loggedIn = File(env.home, ".local/share/Steam/userdata").listFiles()?.any { it.isDirectory } == true
                val extra = if (game == Game.STEAM && AppSettings.steamBigPicture && loggedIn) listOf("-gamepadui") else emptyList()
                val p = xs.launch(game.tag, game.argv(env) + extra)
                if (!current()) return@thread xs.stopAll()
                state = SessionState.Running(game, System.currentTimeMillis())
                if (AppSettings.threadPlacement) ThreadTuner.start(env.files)
                // A game started in the session that installed it: the entry point asks for
                // the rootfs (tools/steam/_v2-entry-point) and waits until the request is gone.
                thread(name = "game-rootfs", isDaemon = true) {
                    val request = GameRootfs.request(env)
                    request.delete()
                    while (current() && p.isAlive) {
                        if (request.exists()) {
                            runCatching { GameRootfs.build(env, ::append) }.onFailure { append("Mediul jocului: eroare: $it") }
                            request.delete()
                        }
                        Thread.sleep(1000)
                    }
                }
                val code = p.waitFor()
                if (current() && state is SessionState.Running) state = SessionState.Exited(game, code)
            } catch (t: Throwable) {
                fail("Eroare: $t")
            }
        }
    }

    /** Stops the game, audio and X server; back to Idle. */
    fun stop() {
        synchronized(this) { generation++ }
        ThreadTuner.stop()
        DisplayBridge.stop()
        bridgeOn = false
        inputReady = false
        // Killing the process trees waits up to ~2 s for a clean exit: not on the UI thread.
        session?.let { old -> stopping = thread(name = "game-stop") { old.stopAll() } }
        session = null
        appContext?.let(GameService::stop)
        state = SessionState.Idle
        playerVisible = false
    }

    /**
     * The surfaces that exist. There can be two for a moment (the player's view is created
     * again when the screen's layout changes while it appears): the picture stays on the one
     * it is on for as long as that one lives, then moves to another one.
     */
    private val surfaces = LinkedHashSet<Surface>()

    fun attachSurface(s: Surface) = synchronized(this) {
        surfaces += s
        if (surface == null) surface = s
        attachBridge()
    }

    fun detachSurface(s: Surface) = synchronized(this) {
        surfaces -= s
        if (surface !== s) return@synchronized
        if (bridgeOn) DisplayBridge.stop()
        bridgeOn = false
        surface = surfaces.lastOrNull()
        attachBridge()
    }

    /** Starts copying X frames to the surface once both exist. */
    private fun attachBridge(): Unit = synchronized(this) {
        val xs = session ?: return
        val s = surface ?: return
        if (bridgeOn || xs.shmid < 0) return
        append(DisplayBridge.start(s, xs.fxshmSocket, xs.shmid, AppSettings.fps))
        bridgeOn = true
        if (!inputReady) {
            append(XInput.connect(0))
            inputReady = true
        }
    }
}
