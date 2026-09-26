package ro.cobrabm.fexdroid

import android.content.Context
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
) {
    companion object {
        /** Does file I/O: call off the main thread. */
        fun check(env: LinuxEnv) = InstallStatus(
            payloadReady = !env.needsInstall() && env.installedVersion() != null,
            payloadProblem = env.pathProblem(),
            steamRootfs = File(env.x86Steam, ".complete").exists(),
            steamClient = File(env.home, ".local/share/Steam/steam.sh").exists(),
            dota = File(env.steamLibrary, "steamapps/common/dota 2 beta/game/dota.sh").exists(),
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
    private var surface: Surface? = null
    private var bridgeOn = false
    private var generation = 0

    private fun append(line: String) {
        Log.i("fexdroid-game", line)
        synchronized(this) { log = (log + line + "\n").takeLast(40_000) }
        val s = state
        if (s is SessionState.Starting && s.step == StartStep.PREPARE && line.isNotBlank())
            state = s.copy(detail = line.trim())
    }

    fun start(ctx: Context, game: Game) {
        if (active && state !is SessionState.Exited && state !is SessionState.Failed) { playerVisible = true; return }
        stop()
        val env = LinuxEnv(ctx.applicationContext)
        val res = AppSettings.resolution
        val gen = synchronized(this) { ++generation }
        val xs = XSession(env, res.width, res.height, ::append)
        session = xs
        resolution = res
        log = ""
        inputReady = false
        state = SessionState.Starting(game, StartStep.PREPARE)
        playerVisible = true
        thread(name = "game-start") {
            fun current() = synchronized(this) { gen == generation }
            fun step(s: StartStep) { if (current()) state = SessionState.Starting(game, s) }
            fun fail(msg: String) { append(msg); if (current()) state = SessionState.Failed(game, msg) }
            try {
                if (game == Game.DOTA && !File(env.steamLibrary, "steamapps/common/dota 2 beta/game/dota.sh").exists())
                    return@thread fail("Dota 2 nu este instalat pe telefon. Vezi Setări › Instalare jocuri.")
                if (!env.ensureInstalled(::append))
                    return@thread fail(env.pathProblem() ?: "Mediul Linux nu a putut fi instalat.")
                if (!current()) return@thread xs.stopAll()
                step(StartStep.DISPLAY)
                if (!xs.startX()) return@thread fail("Ecranul virtual (Xvfb) nu a pornit.")
                if (!current()) return@thread xs.stopAll()
                attachBridge()
                step(StartStep.AUDIO)
                if (!xs.startAudio()) append("Sunetul nu a pornit; continui fără sunet.")
                if (!current()) return@thread xs.stopAll()
                step(StartStep.LAUNCH)
                val p = xs.launch(game.tag, game.argv(env))
                if (!current()) return@thread xs.stopAll()
                state = SessionState.Running(game, System.currentTimeMillis())
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
        DisplayBridge.stop()
        bridgeOn = false
        inputReady = false
        session?.stopAll()
        session = null
        state = SessionState.Idle
        playerVisible = false
    }

    fun attachSurface(s: Surface) {
        surface = s
        attachBridge()
    }

    fun detachSurface() {
        synchronized(this) {
            if (bridgeOn) DisplayBridge.stop()
            bridgeOn = false
            surface = null
        }
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
