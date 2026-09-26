package ro.cobrabm.fexdroid

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback palette (no dynamic color): teal/green "Linux on Android" accents.
private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD8BE), onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF005141), onPrimaryContainer = Color(0xFF9BF4D9),
    secondary = Color(0xFFB2CCC2), secondaryContainer = Color(0xFF344C44),
    tertiary = Color(0xFFA7CCE3), tertiaryContainer = Color(0xFF254B5F),
    background = Color(0xFF0F1513), surface = Color(0xFF0F1513),
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF006B57), primaryContainer = Color(0xFF9BF4D9),
    secondary = Color(0xFF4B635A), tertiary = Color(0xFF3F6377),
)

/** Wallpaper colors (Material You) exist from Android 12. */
val dynamicColorAvailable get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Material3 theme: dark by default, wallpaper colors (Material You) when enabled. */
@Composable
fun FexdroidTheme(content: @Composable () -> Unit) {
    val dark = when (AppSettings.theme) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val ctx = LocalContext.current
    val colors = when {
        dynamicColorAvailable && AppSettings.dynamicColor && dark -> dynamicDarkColorScheme(ctx)
        dynamicColorAvailable && AppSettings.dynamicColor -> dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
