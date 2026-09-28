package ro.cobrabm.fexdroid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Setări: display, appearance, and how to get Steam / games onto the phone. */
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val env = remember { LinuxEnv(ctx) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(str(R.string.settings_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp))

            Section(str(R.string.settings_screen)) {
                Setting(str(R.string.settings_resolution), str(R.string.settings_resolution_about)) {
                    Choice(Resolution.PRESETS, AppSettings.resolution, { "${it.height}p" }, AppSettings::updateResolution)
                }
                SwitchSetting(str(R.string.settings_fit),
                    str(R.string.settings_fit_about),
                    AppSettings.fitScreen, AppSettings::updateFitScreen)
                Setting(str(R.string.settings_margin), str(R.string.settings_margin_about)) {
                    Choice(AppSettings.MARGIN_PRESETS, AppSettings.screenMargin, { if (it == 0) str(R.string.settings_margin_none) else "$it%" },
                        AppSettings::updateScreenMargin)
                }
                SwitchSetting(str(R.string.settings_wide_sides),
                    str(R.string.settings_wide_sides_about),
                    AppSettings.wideSides, AppSettings::updateWideSides)
                Setting(str(R.string.settings_fps), str(R.string.settings_fps_about)) {
                    Choice(AppSettings.FPS_PRESETS, AppSettings.fps, { "$it" }, AppSettings::updateFps)
                }
                if (GameSession.active) Note(str(R.string.settings_after_restart))
            }

            Section(str(R.string.settings_performance)) {
                Setting(str(R.string.settings_fex_profile), str(AppSettings.fexProfile.description)) {
                    Choice(FexProfile.entries, AppSettings.fexProfile, { str(it.label) }, AppSettings::updateFexProfile)
                }
                SwitchSetting(str(R.string.settings_gpu_max),
                    str(R.string.settings_gpu_max_about),
                    AppSettings.gpuMaxFrequency, AppSettings::updateGpuMaxFrequency)
                SwitchSetting(str(R.string.settings_thread_placement),
                    str(R.string.settings_thread_placement_about),
                    AppSettings.threadPlacement, AppSettings::updateThreadPlacement)
                SwitchSetting(str(R.string.settings_video_memory), str(R.string.settings_video_memory_about),
                    AppSettings.limitVideoMemory, AppSettings::updateLimitVideoMemory)
                SwitchSetting(str(R.string.settings_direct_frames), str(R.string.settings_direct_frames_about),
                    AppSettings.directFrames, AppSettings::updateDirectFrames)
                SwitchSetting(str(R.string.settings_source2_tso),
                    str(R.string.settings_source2_tso_about),
                    AppSettings.source2WithoutTso, AppSettings::updateSource2WithoutTso)
                SwitchSetting(str(R.string.settings_dota_profile),
                    str(R.string.settings_dota_profile_about),
                    AppSettings.dotaPerformance, AppSettings::updateDotaPerformance)
                SwitchSetting(str(R.string.settings_disk_cache),
                    str(R.string.settings_disk_cache_about),
                    AppSettings.fexDiskCache, AppSettings::updateFexDiskCache)
                var cacheBytes by remember { mutableStateOf<Long?>(null) }
                LaunchedEffect(Unit) { cacheBytes = withContext(Dispatchers.IO) { runCatching { FexConfig.cacheSize(env) }.getOrNull() } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(str(R.string.settings_cache_size, cacheBytes?.let { "${it shr 20} MB" } ?: "…"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    val scope = rememberCoroutineScope()
                    TextButton(enabled = !GameSession.active, onClick = {
                        scope.launch { cacheBytes = withContext(Dispatchers.IO) { FexConfig.clearCache(env); 0L } }
                    }) { Text(str(R.string.settings_clear_cache)) }
                }
                if (GameSession.active) Note(str(R.string.settings_next_start))
            }

            Section(str(R.string.settings_game)) {
                SwitchSetting(str(R.string.settings_big_picture),
                    str(R.string.settings_big_picture_about),
                    AppSettings.steamBigPicture, AppSettings::updateSteamBigPicture)
                SwitchSetting(
                    str(R.string.settings_without_steam),
                    str(R.string.settings_without_steam_about),
                    AppSettings.gameWithoutSteam, AppSettings::updateGameWithoutSteam,
                )
                Note(str(R.string.settings_without_steam_note))
                // Experiment: Valve's own arm64 client instead of the translated one.
                Setting(str(R.string.settings_steam_arm64), str(R.string.settings_steam_arm64_about)) {
                    TextButton(enabled = !GameSession.active, onClick = { GameSession.start(ctx, Game.STEAM_ARM64) }) {
                        Text(str(R.string.settings_steam_arm64_start))
                    }
                }
            }

            Section(str(R.string.settings_controls)) {
                SwitchSetting(str(R.string.settings_keys), str(R.string.settings_keys_about),
                    AppSettings.onScreenKeys, AppSettings::updateOnScreenKeys)
                if (AppSettings.onScreenKeys) {
                    KeyLayout(str(R.string.settings_keys_left), AppSettings.keysLeft, AppSettings::updateKeysLeft)
                    KeyLayout(str(R.string.settings_keys_right), AppSettings.keysRight, AppSettings::updateKeysRight)
                    Note(str(R.string.settings_keys_help))
                    if (AppSettings.keysLeft != OnScreenKeys.DEFAULT_LEFT || AppSettings.keysRight != OnScreenKeys.DEFAULT_RIGHT) {
                        TextButton(onClick = {
                            AppSettings.updateKeysLeft(OnScreenKeys.DEFAULT_LEFT)
                            AppSettings.updateKeysRight(OnScreenKeys.DEFAULT_RIGHT)
                        }) { Text(str(R.string.settings_keys_default)) }
                    }
                    if (GameSession.active) Note(str(R.string.settings_after_restart))
                }
            }

            Section(str(R.string.settings_appearance)) {
                Setting(str(R.string.settings_language), null) {
                    Choice(Language.entries, AppSettings.language,
                        { if (it == Language.SYSTEM) str(R.string.settings_language_system) else it.label }, AppSettings::updateLanguage)
                }
                Setting(str(R.string.settings_theme), null) {
                    Choice(ThemeMode.entries, AppSettings.theme, {
                        when (it) { ThemeMode.DARK -> str(R.string.settings_theme_dark); ThemeMode.SYSTEM -> str(R.string.settings_theme_system); ThemeMode.LIGHT -> str(R.string.settings_theme_light) }
                    }, AppSettings::updateTheme)
                }
                if (dynamicColorAvailable)
                    SwitchSetting(str(R.string.settings_dynamic_color), "Material You", AppSettings.dynamicColor, AppSettings::updateDynamicColor)
            }

            Section(str(R.string.settings_install)) {
                Note(str(R.string.settings_install_note))
                InstallStep(1, str(R.string.home_linux_env), str(R.string.settings_install_1), env.root.path)
                InstallStep(2, str(R.string.home_steam_libs), str(R.string.settings_install_2), env.x86Steam.path)
                InstallStep(3, str(R.string.home_steam_client), str(R.string.settings_install_3), "${env.home.path}/.local/share/Steam")
                InstallStep(4, "Dota 2", str(R.string.settings_install_4), "${env.steamLibrary.path}/steamapps/common/dota 2 beta")
            }

            val pi = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() }
            Text("fexdroid ${pi ?: ""} · payload ${env.payloadVersion() ?: "—"}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
        }
    }
}

@Composable
private fun Setting(title: String, subtitle: String?, control: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        control()
    }
}

@Composable
private fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o == selected, onClick = { onSelect(o) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label(o), maxLines = 1) }
        }
    }
}

@Composable
private fun SwitchSetting(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked, onChange)
    }
}

/** One side's keys, as the line of names OnScreenKeys reads. */
@Composable
private fun KeyLayout(title: String, value: String, onChange: (String) -> Unit) {
    val unknown = remember(value) { OnScreenKeys.unknown(value) }
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(title) }, singleLine = true,
        isError = unknown.isNotEmpty(),
        supportingText = if (unknown.isEmpty()) null else { { Text(str(R.string.settings_keys_unknown, unknown.joinToString(" "))) } },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun Note(text: String) {
    Row {
        Icon(AppIcons.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InstallStep(n: Int, title: String, how: String, path: String) {
    HorizontalDivider()
    Row {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(28.dp)) {
            Box(contentAlignment = Alignment.Center) { Text("$n", style = MaterialTheme.typography.labelLarge) }
        }
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(how, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Folder, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(6.dp))
                SelectionContainer { Text(path, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline) }
            }
        }
    }
}
