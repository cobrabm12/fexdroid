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
    var fps by mutableIntStateOf(30); private set
    var dynamicColor by mutableStateOf(true); private set
    var theme by mutableStateOf(ThemeMode.DARK); private set
    /** Home screen: Dota 2 direct start (no Steam client) as the main action. */
    var gameWithoutSteam by mutableStateOf(false); private set

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        resolution = Resolution(
            prefs.getInt("x_width", Resolution.DEFAULT.width),
            prefs.getInt("x_height", Resolution.DEFAULT.height),
        )
        fps = prefs.getInt("fps", 30)
        dynamicColor = prefs.getBoolean("dynamic_color", true)
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", null) ?: "DARK") }.getOrDefault(ThemeMode.DARK)
        gameWithoutSteam = prefs.getBoolean("game_without_steam", false)
    }

    fun updateResolution(r: Resolution) {
        resolution = r
        prefs.edit().putInt("x_width", r.width).putInt("x_height", r.height).apply()
    }

    fun updateFps(v: Int) { fps = v; prefs.edit().putInt("fps", v).apply() }
    fun updateDynamicColor(v: Boolean) { dynamicColor = v; prefs.edit().putBoolean("dynamic_color", v).apply() }
    fun updateTheme(v: ThemeMode) { theme = v; prefs.edit().putString("theme", v.name).apply() }
    fun updateGameWithoutSteam(v: Boolean) { gameWithoutSteam = v; prefs.edit().putBoolean("game_without_steam", v).apply() }
}
