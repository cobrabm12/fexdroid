package ro.cobrabm.fexdroid

import android.util.Log
import android.view.SurfaceHolder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlin.concurrent.thread

/**
 * Developer X11 test screen (Avansat › Ecran X11): Xvfb + bridge + test clients.
 * Also the target of the `--es action x|vkcube|fex-vkcube|audio-test|fex-audio-test|xev|steam|dota` adb intents.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DisplayScreen(autoAction: String?) {
    val ctx = LocalContext.current
    val env = remember { LinuxEnv(ctx) }
    var log by remember { mutableStateOf("") }
    fun append(line: String) {
        Log.i("fexdroid-display", line)
        log = (log + line + "\n").takeLast(6000)
    }
    val session = remember {
        val r = AppSettings.resolution
        XSession(env, r.width, r.height) { line -> append(line) }
    }
    var holder by remember { mutableStateOf<SurfaceHolder?>(null) }
    var view by remember { mutableStateOf<InputSurfaceView?>(null) }
    var frames by remember { mutableStateOf(0L) }

    fun startX() = thread(name = "start-x") {
        if (!env.ensureInstalled(::append)) return@thread
        if (!session.startX()) { append("Xvfb nu a raportat un framebuffer (vezi log)."); return@thread }
        val h = holder ?: run { append("Suprafața nu e gata."); return@thread }
        append(DisplayBridge.start(h.surface, session.fxshmSocket, session.shmid, AppSettings.fps))
        append(XInput.connect(0))
        view?.post { view?.inputEnabled = true; view?.requestFocus() }
    }

    /** Phase 5: Steam client under FEX (tools/steam/fexdroid-steam.sh). */
    fun startSteam() {
        startX().join()
        session.startAudio()
        session.launch("steam", Game.STEAM.argv(env))
    }

    /** Dota 2 without the Steam client (bring-up); tools/steam/fexdroid-dota.sh. */
    fun startDota() {
        startX().join()
        session.startAudio()
        session.launch("dota", Game.DOTA.argv(env))
    }

    fun sh(title: String, cmd: String) = thread {
        startX().join()
        if (session.startAudio()) session.launch(title, listOf("${env.root}/bin/sh", "-c", cmd))
    }
    val toneRaw = "--raw --format=s16le --rate=48000 --channels=2"

    DisposableEffect(Unit) { onDispose { DisplayBridge.stop(); thread(name = "x-stop") { session.stopAll() } } }
    var audioFrames by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) { frames = DisplayBridge.frames(); audioFrames = AudioBridge.frames(); delay(1000) }
    }
    var autoDone by remember { mutableStateOf(false) }
    LaunchedEffect(autoAction, holder) {
        if (holder == null || autoDone) return@LaunchedEffect
        autoDone = true
        when (autoAction) {
            "x" -> startX()
            "vkcube" -> thread { startX().join(); session.launch("vkcube", listOf("${env.root}/usr/bin/vkcube")) }
            "audio-test" -> sh("tone", "${env.root}/opt/fexdroid-tests/tone 3 | pacat $toneRaw")
            "fex-audio-test" -> sh("tone-x86",
                "${env.root}/opt/fexdroid-tests/tone 3 | ${env.root}/usr/bin/FEX ${env.x86Root}/usr/bin/pacat $toneRaw")
            "steam" -> thread { startSteam() }
            "dota" -> thread { startDota() }
            "xev" -> thread { startX().join(); session.launch("xev", listOf("${env.root}/bin/sh", "-c",
                // Line-buffered stdout (what `stdbuf -oL` does) so events reach the log at once.
                "LD_PRELOAD=${env.root}/usr/libexec/coreutils/libstdbuf.so _STDBUF_O=L exec \"$@\"", "sh", "${env.root}/usr/bin/xev", "-geometry", "${session.width}x${session.height}+0+0", "-event", "keyboard", "-event", "button", "-event", "mouse")) }
            "fex-vkcube" -> thread {
                startX().join()
                session.launch("vkcube-x86", listOf("${env.root}/usr/bin/FEX", "${env.x86Root}/usr/bin/vkcube"))
            }
        }
    }

    Column(Modifier.padding(8.dp)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(session.width.toFloat() / session.height),
            factory = { c ->
                InputSurfaceView(c, session.width, session.height).apply {
                    view = this
                    this.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) { holder = h }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) { DisplayBridge.stop(); holder = null }
                    })
                }
            },
        )
        Text("cadre afișate: $frames · audio: ${audioFrames / 48000} s redate", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { startX() }) { Text("Pornește X") }
            Button(onClick = { thread { startSteam() } }) { Text("Steam") }
            Button(onClick = { thread { startDota() } }) { Text("Dota 2 (direct)") }
            Button(onClick = { thread { startX().join(); session.launch("vkcube", listOf("${env.root}/usr/bin/vkcube")) } }) {
                Text("vkcube arm64")
            }
            Button(onClick = {
                thread {
                    startX().join()
                    session.launch("vkcube-x86", listOf("${env.root}/usr/bin/FEX", "${env.x86Root}/usr/bin/vkcube"))
                }
            }) { Text("vkcube x86 (FEX)") }
            OutlinedButton(onClick = { view?.requestPointerCapture() }) { Text("Captează mouse (Ctrl+Alt eliberează)") }
            OutlinedButton(onClick = {
                view?.let { v ->
                    v.requestFocus()
                    ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .showSoftInput(v, 0)
                }
            }) { Text("Tastatură") }
            Button(onClick = { sh("tone", "${env.root}/opt/fexdroid-tests/tone 3 | pacat $toneRaw") }) { Text("Test sunet") }
            Button(onClick = {
                sh("tone-x86", "${env.root}/opt/fexdroid-tests/tone 3 | ${env.root}/usr/bin/FEX ${env.x86Root}/usr/bin/pacat $toneRaw")
            }) { Text("Test sunet x86 (FEX)") }
            Button(onClick = { thread { startX().join(); session.launch("xev", listOf("${env.root}/usr/bin/xev")) } }) { Text("xev") }
            OutlinedButton(onClick = { DisplayBridge.stop(); thread(name = "x-stop") { session.stopAll() } }) { Text("Oprește tot") }
        }
        Text(log, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}
