package ro.cobrabm.fexdroid

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import java.io.File

/** Bottom navigation destinations. */
enum class Dest(val label: String, val icon: ImageVector) {
    HOME("Acasă", AppIcons.Home),
    SETTINGS("Setări", AppIcons.Settings),
    ADVANCED("Avansat", AppIcons.Build),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.init(this)
        enableEdgeToEdge()
        // Games and long test runs: never let the screen time out while we are visible.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Android 13+: the game notification (GameService) needs this; the service runs without it too.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        // `adb shell am start -n <pkg>/ro.cobrabm.fexdroid.MainActivity --ez autorun true`
        // `--es action phase1|fex-static|fex-dynamic|vulkaninfo|fex-vulkaninfo` runs a Linux step
        // (output in files/linux.txt); `--es action x|vkcube|fex-vkcube` opens the X11 screen.
        // Any of these extras opens Avansat (the developer screens) instead of Acasă.
        val autorun = intent.getBooleanExtra("autorun", false)
        val action = intent.getStringExtra("action")
        val cmd = intent.getStringExtra("cmd")
        val devIntent = autorun || action != null
        // `--es action steam|dota` starts the game session at once, without waiting for a
        // visible surface (works with the screen locked; the player attaches when shown).
        when (action) {
            "steam" -> GameSession.start(this, Game.STEAM)
            "dota" -> GameSession.start(this, Game.DOTA)
        }
        setContent {
            FexdroidTheme {
                Surface(Modifier.fillMaxSize()) {
                    var dest by rememberSaveable { mutableStateOf(if (devIntent) Dest.ADVANCED else Dest.HOME) }
                    if (GameSession.active && GameSession.playerVisible) {
                        PlayerScreen()
                    } else {
                        Scaffold(
                            bottomBar = {
                                NavigationBar {
                                    for (d in Dest.entries) NavigationBarItem(
                                        selected = dest == d, onClick = { dest = d },
                                        icon = { Icon(d.icon, null) }, label = { Text(d.label) },
                                    )
                                }
                            },
                        ) { padding ->
                            Surface(Modifier.fillMaxSize().padding(padding)) {
                                when (dest) {
                                    Dest.HOME -> HomeScreen(onOpenSettings = { dest = Dest.SETTINGS })
                                    Dest.SETTINGS -> SettingsScreen()
                                    Dest.ADVANCED -> AdvancedScreen(advancedTabFor(action), autorun, action, cmd)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Wheel and button events of a mouse over the game's picture that the Compose hierarchy did
     * not hand to the view showing it (it forwards pointer moves and the touch stream, not
     * every generic motion event).
     */
    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        val view = InputSurfaceView.current
        val handled = super.dispatchGenericMotionEvent(ev)
        if (view == null || !view.inputEnabled || InputSurfaceView.overlayOpen ||
            !ev.isFromSource(android.view.InputDevice.SOURCE_MOUSE) || view.lastMouseEventTime == ev.eventTime) return handled
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_SCROLL, android.view.MotionEvent.ACTION_BUTTON_PRESS,
            android.view.MotionEvent.ACTION_BUTTON_RELEASE -> {}
            else -> return handled
        }
        val at = IntArray(2).also(view::getLocationOnScreen)
        val inside = ev.rawX >= at[0] && ev.rawX < at[0] + view.width && ev.rawY >= at[1] && ev.rawY < at[1] + view.height
        return if (inside) view.mouseEvent(ev, located = false) else handled
    }

    /** A mouse's second button arrives as the Back key: see [InputSurfaceView.mouseBackKey]. */
    override fun dispatchKeyEvent(ev: android.view.KeyEvent): Boolean {
        val view = InputSurfaceView.current
        if (view != null && view.inputEnabled && !InputSurfaceView.overlayOpen &&
            ev.keyCode == android.view.KeyEvent.KEYCODE_BACK && ev.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) {
            when (ev.action) {
                android.view.KeyEvent.ACTION_DOWN -> if (ev.repeatCount == 0) view.mouseBackKey(true)
                android.view.KeyEvent.ACTION_UP -> view.mouseBackKey(false)
            }
            return true
        }
        return super.dispatchKeyEvent(ev)
    }

    override fun onDestroy() {
        // Leaving the app for good: do not keep Xvfb/PulseAudio/the game running without a UI.
        if (isFinishing) GameSession.stop()
        super.onDestroy()
    }

    /** Output file, readable over adb without root: /sdcard/Android/data/<pkg>/files/recon.txt */
    fun reportFile(): File = File(getExternalFilesDir(null), "recon.txt")

    fun share(text: String, subject: String = "fexdroid recon") {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Trimite raportul"))
    }
}
