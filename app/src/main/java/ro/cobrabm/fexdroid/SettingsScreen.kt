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
            Text("Setări", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp))

            Section("Ecran") {
                Setting("Rezoluție", "Mărimea ecranului virtual. Mai mică = mai rapid. Se aplică la următoarea pornire.") {
                    Choice(Resolution.PRESETS, AppSettings.resolution, { "${it.height}p" }, AppSettings::updateResolution)
                }
                SwitchSetting("Potrivește la ecranul telefonului",
                    "Lățimea ecranului virtual urmează forma ecranului, fără benzi negre. Oprit: 16:9.",
                    AppSettings.fitScreen, AppSettings::updateFitScreen)
                Setting("Margine", "Distanța imaginii față de marginile ecranului. Colțurile rotunjite și camera acoperă ce " +
                    "desenează jocul acolo, iar atingerile de pe margine se pierd des.") {
                    Choice(AppSettings.MARGIN_PRESETS, AppSettings.screenMargin, { if (it == 0) "Fără" else "$it%" },
                        AppSettings::updateScreenMargin)
                }
                SwitchSetting("Margini laterale late",
                    "Imaginea stă mai departe de marginile din stânga și din dreapta. Pe realme, OPPO și OnePlus bara " +
                        "de jocuri a telefonului acoperă marginea stângă: butoanele jocului de acolo nu se pot atinge.",
                    AppSettings.wideSides, AppSettings::updateWideSides)
                Setting("Cadre pe secundă", "Cât de des se caută o imagine nouă de la joc. Se copiază pe ecran doar imaginile noi.") {
                    Choice(AppSettings.FPS_PRESETS, AppSettings.fps, { "$it" }, AppSettings::updateFps)
                }
                if (GameSession.active) Note("Un joc rulează acum: noile valori se aplică după repornire.")
            }

            Section("Performanță") {
                Setting("Profil FEX", AppSettings.fexProfile.description) {
                    Choice(FexProfile.entries, AppSettings.fexProfile, { it.label }, AppSettings::updateFexProfile)
                }
                SwitchSetting("Placa grafică la viteză maximă",
                    "Jocurile cer plăcii grafice frecvența cea mai mare cât desenează. Mai multe cadre, telefon mai cald. " +
                        "Doar pe telefoane cu Adreno.",
                    AppSettings.gpuMaxFrequency, AppSettings::updateGpuMaxFrequency)
                SwitchSetting("Nucleul cel mai rapid pentru firul principal",
                    "Firul cel mai ocupat al jocului primește singur nucleul cel mai rapid al telefonului; restul " +
                        "sesiunii rulează pe celelalte nuclee.",
                    AppSettings.threadPlacement, AppSettings::updateThreadPlacement)
                SwitchSetting("Profil de performanță pentru Dota 2",
                    "Setările video ale jocului pe cele mai ieftine valori: fără umbre, efecte puține, texturi mai mici. " +
                        "Se aplică la pornirea lui Steam, după ce jocul a fost pornit o dată. Oprit: revin setările tale.",
                    AppSettings.dotaPerformance, AppSettings::updateDotaPerformance)
                SwitchSetting("Cache de cod pe disc",
                    "Experimental. Codul x86 tradus o dată e păstrat și refolosit, deci pornirile următoare sunt mai rapide. Cu FEX-2609 unele programe crapă (de ex. instalarea Steam), așa că e oprit implicit.",
                    AppSettings.fexDiskCache, AppSettings::updateFexDiskCache)
                var cacheBytes by remember { mutableStateOf<Long?>(null) }
                LaunchedEffect(Unit) { cacheBytes = withContext(Dispatchers.IO) { runCatching { FexConfig.cacheSize(env) }.getOrNull() } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cache: ${cacheBytes?.let { "${it shr 20} MB" } ?: "…"}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    val scope = rememberCoroutineScope()
                    TextButton(enabled = !GameSession.active, onClick = {
                        scope.launch { cacheBytes = withContext(Dispatchers.IO) { FexConfig.clearCache(env); 0L } }
                    }) { Text("Șterge cache-ul") }
                }
                if (GameSession.active) Note("Se aplică la următoarea pornire a jocului.")
            }

            Section("Joc") {
                SwitchSetting("Steam în Big Picture",
                    "Interfața pe tot ecranul, pentru atingere și controller. Prima autentificare se face în interfața clasică.",
                    AppSettings.steamBigPicture, AppSettings::updateSteamBigPicture)
                SwitchSetting(
                    "Rulează jocul fără Steam",
                    "Butonul principal de pe Acasă devine „Pornește Dota 2 (direct)”.",
                    AppSettings.gameWithoutSteam, AppSettings::updateGameWithoutSteam,
                )
                Note("Fără clientul Steam, jocul pornește doar pentru test: fără autentificare, " +
                    "fără meciuri online și fără actualizări. Pentru joc normal folosește Steam.")
            }

            Section("Aspect") {
                Setting("Temă", null) {
                    Choice(ThemeMode.entries, AppSettings.theme, {
                        when (it) { ThemeMode.DARK -> "Întunecată"; ThemeMode.SYSTEM -> "Sistem"; ThemeMode.LIGHT -> "Luminoasă" }
                    }, AppSettings::updateTheme)
                }
                if (dynamicColorAvailable)
                    SwitchSetting("Culori din imaginea de fundal", "Material You", AppSettings.dynamicColor, AppSettings::updateDynamicColor)
            }

            Section("Instalare Steam și jocuri") {
                Note("Deocamdată fișierele mari se copiază de pe un PC cu Linux, prin USB (adb), " +
                    "cu scripturile din depozitul fexdroid. Contul Steam nu se copiază: te autentifici pe telefon cu codul QR.")
                InstallStep(1, "Mediul Linux", "Inclus în aplicație; se instalează singur la prima pornire.", env.root.path)
                InstallStep(2, "Biblioteci Steam (x86)",
                    "Pe PC: scripts/build-rootfs-steam.sh, apoi scripts/deploy-phone.sh (sau doar pasul „Steam x86 rootfs” din el).",
                    env.x86Steam.path)
                InstallStep(3, "Client Steam",
                    "Se descarcă automat de la Valve la prima pornire, sau se copiază de pe PC cu scripts/adb-copy-steam-client.sh.",
                    "${env.home.path}/.local/share/Steam")
                InstallStep(4, "Dota 2",
                    "Instalează-l întâi în Steam pe PC, apoi: scripts/adb-copy-steam-game.sh 570 (~70 GB, se poate relua).",
                    "${env.steamLibrary.path}/steamapps/common/dota 2 beta")
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
