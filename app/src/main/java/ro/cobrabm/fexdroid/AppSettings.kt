package ro.cobrabm.fexdroid

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Screen size of the virtual X display (Xvfb). */
data class Resolution(val width: Int, val height: Int) {
    val label get() = "${width}×$height"
    companion object {
        val PRESETS = listOf(Resolution(1280, 720), Resolution(1600, 900), Resolution(1920, 1080))
        val DEFAULT = PRESETS[0]

        /**
         * [base] widened or narrowed to the shape of the phone's screen in landscape, so the
         * picture fills it without black bars (20:9 phone: 1280x720 becomes 1600x720). The
         * height, which sets the rendering cost class, stays. Shapes are limited to 4:3..21:9.
         */
        fun fitted(ctx: Context, base: Resolution): Resolution {
            val wm = ctx.getSystemService(android.view.WindowManager::class.java) ?: return base
            val (a, b) = if (android.os.Build.VERSION.SDK_INT >= 30) {
                wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
            } else {
                val m = android.util.DisplayMetrics()
                @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
                m.widthPixels to m.heightPixels
            }
            if (a <= 0 || b <= 0) return base
            val aspect = (maxOf(a, b).toDouble() / minOf(a, b)).coerceIn(4.0 / 3.0, 21.0 / 9.0)
            val width = (Math.round(base.height * aspect / 8.0) * 8).toInt()
            return Resolution(width, base.height)
        }
    }
}

enum class ThemeMode { DARK, SYSTEM, LIGHT }

/**
 * User settings, persisted in SharedPreferences and exposed as Compose state.
 * Call [init] once (MainActivity.onCreate) before reading.
 */
object AppSettings {
    val FPS_PRESETS = listOf(30, 45, 60)

    private lateinit var prefs: SharedPreferences

    var resolution by mutableStateOf(Resolution.DEFAULT); private set
    /** Virtual screen follows the shape of the phone's screen ([Resolution.fitted]). */
    var fitScreen by mutableStateOf(true); private set
    var fps by mutableIntStateOf(30); private set
    var dynamicColor by mutableStateOf(true); private set
    var theme by mutableStateOf(ThemeMode.DARK); private set
    /** Home screen: Dota 2 direct start (no Steam client) as the main action. */
    var gameWithoutSteam by mutableStateOf(false); private set
    /** Steam starts in Big Picture (-gamepadui): full screen, made for touch and controllers. */
    var steamBigPicture by mutableStateOf(false); private set
    /** FEX speed/accuracy trade-off (FexConfig), written before every game start. */
    var fexProfile by mutableStateOf(FexProfile.BALANCED); private set
    /**
     * FEX JIT disk cache: faster loading after the first start of a game. Off by default:
     * with FEX-2609 it makes Steam's runtime setup.sh segfault (NOTES N-028). New pref key so
     * the old default (on) does not stick.
     */
    var fexDiskCache by mutableStateOf(false); private set

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        resolution = Resolution(
            prefs.getInt("x_width", Resolution.DEFAULT.width),
            prefs.getInt("x_height", Resolution.DEFAULT.height),
        )
        fitScreen = prefs.getBoolean("fit_screen", true)
        fps = prefs.getInt("fps", 30)
        dynamicColor = prefs.getBoolean("dynamic_color", true)
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", null) ?: "DARK") }.getOrDefault(ThemeMode.DARK)
        gameWithoutSteam = prefs.getBoolean("game_without_steam", false)
        steamBigPicture = prefs.getBoolean("steam_big_picture", false)
        fexProfile = runCatching { FexProfile.valueOf(prefs.getString("fex_profile", null) ?: "BALANCED") }
            .getOrDefault(FexProfile.BALANCED)
        fexDiskCache = prefs.getBoolean("fex_disk_cache_v2", false)
    }

    fun updateResolution(r: Resolution) {
        resolution = r
        prefs.edit().putInt("x_width", r.width).putInt("x_height", r.height).apply()
    }

    fun updateFitScreen(v: Boolean) { fitScreen = v; prefs.edit().putBoolean("fit_screen", v).apply() }
    /** The X screen size to start a session with. */
    fun sessionResolution(ctx: Context) = if (fitScreen) Resolution.fitted(ctx, resolution) else resolution

    fun updateFps(v: Int) { fps = v; prefs.edit().putInt("fps", v).apply() }
    fun updateDynamicColor(v: Boolean) { dynamicColor = v; prefs.edit().putBoolean("dynamic_color", v).apply() }
    fun updateTheme(v: ThemeMode) { theme = v; prefs.edit().putString("theme", v.name).apply() }
    fun updateFexProfile(v: FexProfile) { fexProfile = v; prefs.edit().putString("fex_profile", v.name).apply() }
    fun updateFexDiskCache(v: Boolean) { fexDiskCache = v; prefs.edit().putBoolean("fex_disk_cache_v2", v).apply() }
    fun updateSteamBigPicture(v: Boolean) { steamBigPicture = v; prefs.edit().putBoolean("steam_big_picture", v).apply() }
    fun updateGameWithoutSteam(v: Boolean) { gameWithoutSteam = v; prefs.edit().putBoolean("game_without_steam", v).apply() }
}
