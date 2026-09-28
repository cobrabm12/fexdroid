package ro.cobrabm.fexdroid

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.SurfaceHolder
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Full-screen game view: the X screen (landscape, immersive, letterboxed), a floating
 * menu button, and panels for startup progress, errors and the raw log.
 */
@Composable
fun PlayerScreen() {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    val state = GameSession.state
    val res = GameSession.resolution
    var view by remember { mutableStateOf<InputSurfaceView?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var logOpen by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var keyboardShown by remember { mutableStateOf(false) }
    var mouseCaptured by remember { mutableStateOf(false) }

    // Landscape + hidden system bars while the player is shown.
    DisposableEffect(Unit) {
        val prev = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // Compat controller: WindowInsetsController itself is Android 11+ (minSdk is 28).
        val ic = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        ic.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ic.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            ic.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = prev
            view?.releasePointerCapture()
            OnScreenKeys.reset()
        }
    }

    androidx.compose.runtime.SideEffect { InputSurfaceView.overlayOpen = menuOpen || logOpen || confirmStop }
    DisposableEffect(Unit) { onDispose { InputSurfaceView.overlayOpen = false } }

    BackHandler {
        when {
            logOpen -> logOpen = false
            menuOpen -> menuOpen = false
            state is SessionState.Running || state is SessionState.Starting -> menuOpen = true
            else -> GameSession.stop()
        }
    }

    fun toggleKeyboard() {
        val v = view ?: return
        val imm = ctx.getSystemService(InputMethodManager::class.java)
        if (keyboardShown) imm.hideSoftInputFromWindow(v.windowToken, 0)
        else { v.requestFocus(); imm.showSoftInput(v, 0) }
        keyboardShown = !keyboardShown
    }

    fun toggleMouse() {
        val v = view ?: return
        if (v.hasPointerCapture()) { v.releasePointerCapture(); mouseCaptured = false }
        else { v.requestFocus(); v.requestPointerCapture(); mouseCaptured = true }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center,
    ) {
        // The margin the session's resolution was computed for (AppSettings.screenMargin).
        val margin = minOf(maxWidth, maxHeight) * (AppSettings.screenMargin / 100f)
        val edge = if (AppSettings.wideSides && margin < AppSettings.WIDE_SIDE_DP.dp) AppSettings.WIDE_SIDE_DP.dp else margin
        val side = edge + AppSettings.keysDp.dp
        // Recreate the view when the X resolution changes (it maps touches to X coordinates).
        androidx.compose.runtime.key(res) {
            AndroidView(
                modifier = Modifier.padding(horizontal = side, vertical = margin).fillMaxHeight()
                    .aspectRatio(res.width.toFloat() / res.height, matchHeightConstraintsFirst = true),
                factory = { c ->
                    InputSurfaceView(c, res.width, res.height).apply {
                        view = this
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) { GameSession.attachSurface(h.surface) }
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                            override fun surfaceDestroyed(h: SurfaceHolder) { GameSession.detachSurface(h.surface) }
                        })
                    }
                },
                update = { v ->
                    val enable = GameSession.inputReady
                    if (enable && !v.inputEnabled) v.post { v.requestFocus() }
                    v.inputEnabled = enable
                },
            )
        }

        when (state) {
            is SessionState.Starting -> StartupPanel(state, onLog = { logOpen = true }, onCancel = { GameSession.stop() })
            is SessionState.Exited -> EndPanel(
                title = str(R.string.session_closed, state.game.title),
                // Steam's start script ends with code 0 whatever happened to Steam itself.
                message = if (state.code == 0) str(R.string.player_closed_itself)
                    else str(R.string.player_stopped_code, state.code),
                game = state.game, onLog = { logOpen = true },
            )
            is SessionState.Failed -> EndPanel(str(R.string.session_did_not_start, state.game.title), state.message, state.game, onLog = { logOpen = true })
            is SessionState.Running -> { RunningHint(state); MemoryWarning() }
            SessionState.Idle -> {}
        }

        val keys = AppSettings.onScreenKeys && state is SessionState.Running && GameSession.inputReady
        if (keys) {
            OnScreenKeys.Strip("left", remember(AppSettings.keysLeft) { OnScreenKeys.parse(AppSettings.keysLeft) },
                Modifier.align(Alignment.CenterStart).padding(start = edge))
            // The menu button would sit on the keys: it is the first of them.
            OnScreenKeys.Strip("right", remember(AppSettings.keysRight) { listOf(OnScreenKeys.Menu) + OnScreenKeys.parse(AppSettings.keysRight) },
                Modifier.align(Alignment.CenterEnd).padding(end = edge), onMenu = { menuOpen = !menuOpen })
        } else if (state is SessionState.Running || state is SessionState.Starting) {
            // Middle of the right edge: games keep their own buttons and figures in the corners.
            FloatingMenuButton(Modifier.align(Alignment.CenterEnd).displayCutoutPadding()) { menuOpen = !menuOpen }
        }

        AnimatedVisibility(menuOpen, Modifier.align(Alignment.TopEnd).displayCutoutPadding(), enter = fadeIn(), exit = fadeOut()) {
            GameMenu(
                keyboardShown = keyboardShown, mouseCaptured = mouseCaptured,
                onKeyboard = { menuOpen = false; toggleKeyboard() },
                onKeys = { menuOpen = false; AppSettings.updateOnScreenKeys(!AppSettings.onScreenKeys) },
                onMouse = { menuOpen = false; toggleMouse() },
                onLog = { menuOpen = false; logOpen = true },
                onMinimize = { menuOpen = false; GameSession.playerVisible = false },
                onStop = { menuOpen = false; confirmStop = true },
                onClose = { menuOpen = false },
            )
        }

        if (logOpen) LogPanel { logOpen = false }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(str(R.string.player_stop_question)) },
            text = { Text(str(R.string.player_stop_warning)) },
            confirmButton = { Button(onClick = { confirmStop = false; GameSession.stop() }) { Text(str(R.string.stop)) } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(str(R.string.cancel)) } },
        )
    }
}

@Composable
private fun FloatingMenuButton(modifier: Modifier, onClick: () -> Unit) {
    var dy by remember { mutableFloatStateOf(0f) }
    Box(
        modifier
            .offset { IntOffset(0, dy.roundToInt()) } // Can be dragged up and down.
            .padding(4.dp)
            .size(36.dp)
            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
            .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); dy += drag.y } }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(AppIcons.Menu, str(R.string.player_menu), Modifier.size(20.dp), tint = Color.White.copy(alpha = 0.8f))
    }
}

@Composable
private fun GameMenu(
    keyboardShown: Boolean, mouseCaptured: Boolean,
    onKeyboard: () -> Unit, onKeys: () -> Unit, onMouse: () -> Unit, onLog: () -> Unit,
    onMinimize: () -> Unit, onStop: () -> Unit, onClose: () -> Unit,
) {
    var frames by remember { mutableLongStateOf(DisplayBridge.changedFrames()) }
    var fps by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000); val f = DisplayBridge.changedFrames(); fps = f - frames; frames = f }
    }
    Card(
        Modifier.padding(12.dp).widthIn(max = 300.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f)),
    ) {
        // Taller than a phone's screen in landscape: it scrolls.
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(str(R.string.player_menu), style = MaterialTheme.typography.titleMedium)
                    Text(str(R.string.player_menu_status, GameSession.resolution.label, fps), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onClose) { Icon(AppIcons.Close, str(R.string.close)) }
            }
            MenuItem(AppIcons.Keyboard, if (keyboardShown) str(R.string.player_hide_keyboard) else str(R.string.player_keyboard), onKeyboard)
            MenuItem(AppIcons.Keyboard, str(if (AppSettings.onScreenKeys) R.string.player_keys_hide else R.string.player_keys_show),
                onKeys, str(R.string.player_keys_hint))
            MenuItem(AppIcons.Mouse, if (mouseCaptured) str(R.string.player_release_mouse) else str(R.string.player_capture_mouse),
                onMouse, str(R.string.player_capture_hint))
            MenuItem(AppIcons.Log, str(R.string.player_log), onLog)
            MenuItem(AppIcons.Back, str(R.string.player_minimize), onMinimize, str(R.string.player_minimize_hint))
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            MenuItem(AppIcons.Stop, str(R.string.stop), onStop, tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, text: String, onClick: () -> Unit, hint: String? = null, tint: Color = Color.Unspecified) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = if (tint == Color.Unspecified) Color.Unspecified else tint)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StartupPanel(st: SessionState.Starting, onLog: () -> Unit, onCancel: () -> Unit) {
    PanelCard {
        Text(str(R.string.player_starting, st.game.title), style = MaterialTheme.typography.headlineSmall)
        LinearProgressIndicator(
            progress = { (st.step.ordinal + 0.5f) / StartStep.entries.size },
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )
        for (step in StartStep.entries) {
            val label = if (step == StartStep.LAUNCH) str(R.string.player_starting, st.game.title) else step.label
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    step.ordinal < st.step.ordinal -> Icon(AppIcons.CheckCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    step == st.step -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else -> Icon(AppIcons.Pending, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
                }
                Spacer(Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge,
                    color = if (step.ordinal > st.step.ordinal) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
            }
        }
        if (st.step == StartStep.PREPARE && st.detail.isNotEmpty()) {
            Text(st.detail, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 32.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onLog) { Text(str(R.string.player_show_log)) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onCancel) { Text(str(R.string.cancel)) }
        }
    }
}

@Composable
private fun EndPanel(title: String, message: String, game: Game, onLog: () -> Unit) {
    val ctx = LocalContext.current
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Error, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall)
        }
        Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { shareSessionReport(ctx) }) { Text(str(R.string.player_send_log)) }
            TextButton(onClick = onLog) { Text(str(R.string.player_show_log)) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { GameSession.stop() }) { Text(str(R.string.close)) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { GameSession.start(ctx, game) }) { Text(str(R.string.try_again)) }
        }
    }
}

/** Shown while memory is about to run out (MemoryWatch): the match can still be finished. */
@Composable
private fun MemoryWarning() {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(MemoryWatch.low, enter = fadeIn(), exit = fadeOut()) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                Text(str(R.string.player_memory_low), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.widthIn(max = 420.dp).padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

/** Short hint right after the game starts (the first frames can take a while under FEX). */
@Composable
private fun RunningHint(st: SessionState.Running) {
    var visible by remember(st) { mutableStateOf(true) }
    LaunchedEffect(st) { delay(10_000); visible = false }
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White) {
                Text(str(R.string.player_loading_hint, st.game.title),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun PanelCard(content: @Composable () -> Unit) {
    Card(Modifier.padding(24.dp).widthIn(max = 520.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) { content() }
    }
}

@Composable
private fun LogPanel(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scroll = rememberScrollState()
    val log = GameSession.log
    LaunchedEffect(log) { scroll.scrollTo(scroll.maxValue) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(str(R.string.player_log), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { shareSessionReport(ctx) }) { Text(str(R.string.player_send)) }
                IconButton(onClick = onClose) { Icon(AppIcons.Close, str(R.string.close)) }
            }
            SelectionContainer(Modifier.fillMaxSize().verticalScroll(scroll).horizontalScroll(rememberScrollState())) {
                Text(log.ifEmpty { str(R.string.player_log_empty) }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

/**
 * The session log with what a developer needs to read it (app and payload version, device
 * checks), through Android's share sheet: testers are not next to a PC with adb.
 */
internal fun shareSessionReport(ctx: android.content.Context) {
    val activity = ctx as? MainActivity ?: return
    android.widget.Toast.makeText(ctx, str(R.string.report_preparing), android.widget.Toast.LENGTH_SHORT).show()
    kotlin.concurrent.thread(name = "session-report") { // The self-tests and device checks take a few seconds.
        val text = GameSession.reportOnce(ctx) ?: return@thread // Already preparing one.
        // Also readable over adb without root: /sdcard/Android/data/<pkg>/files/report.txt
        runCatching { java.io.File(ctx.getExternalFilesDir(null), "report.txt").writeText(text) }
        activity.runOnUiThread { activity.share(text, str(R.string.report_subject)) }
    }
}
