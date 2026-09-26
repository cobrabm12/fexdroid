package ro.cobrabm.fexdroid

import android.util.Log
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** adb `--es action` values handled by the X11 test screen (tab 2); other actions go to the Linux tab. */
val DISPLAY_ACTIONS = setOf("x", "vkcube", "fex-vkcube", "audio-test", "fex-audio-test", "xev", "steam", "dota")

/** Initial Avansat tab for the adb extras (same mapping as the old tab-only UI). */
fun advancedTabFor(action: String?): Int = if (action in DISPLAY_ACTIONS) 2 else if (action != null) 1 else 0

/**
 * Avansat / Dezvoltator: the original test harness (phase 0 recon, Linux phases 1–3, X11 screen),
 * also the target of the adb intents (`--ez autorun`, `--es action …`, `--es cmd …`).
 */
@Composable
fun AdvancedScreen(initialTab: Int, autorun: Boolean, action: String?, cmd: String?) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Recunoaștere") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Linux · Faza 1–3") })
            Tab(tab == 2, { tab = 2 }, text = { Text("Ecran X11") })
        }
        when (tab) {
            0 -> ReconScreen(autorun)
            1 -> LinuxScreen(action, cmd)
            else -> if (GameSession.active) SessionBusy() else DisplayScreen(action)
        }
    }
}

/** The dev X11 screen and a user game session would fight over display :0 and the bridge. */
@Composable
private fun SessionBusy() {
    Card(Modifier.padding(16.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Info, null)
                Spacer(Modifier.width(12.dp))
                Text("Un joc rulează", style = MaterialTheme.typography.titleMedium)
            }
            Text("Ecranul de test X11 folosește același ecran virtual. Oprește jocul ca să-l deschizi.",
                style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { GameSession.stop() }) { Text("Oprește jocul") }
        }
    }
}

@Composable
fun ReconScreen(autorun: Boolean) {
    val activity = androidx.compose.ui.platform.LocalContext.current as MainActivity
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Apasă „Rulează” pentru recunoașterea telefonului.") }
    var running by remember { mutableStateOf(false) }

    fun run() {
        if (running) return
        running = true
        scope.launch {
            val recon = Recon(activity)
            val sb = StringBuilder(recon.header())
            report = sb.toString()
            for ((name, probe) in recon.sections()) {
                status = "Rulez: $name…"
                val text = withContext(Dispatchers.IO) {
                    runCatching(probe).getOrElse { "EXCEPTION: $it" }
                }
                sb.append("\n== ").append(name).append(" ==\n").append(text.trimEnd()).append('\n')
                report = sb.toString()
            }
            val file = activity.reportFile()
            withContext(Dispatchers.IO) {
                file.writeText(report)
                report.lines().forEach { Log.i("fexdroid-recon", it) }
            }
            status = "Gata. Salvat în ${file.absolutePath}"
            running = false
        }
    }

    LaunchedEffect(Unit) { if (autorun) run() }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("fexdroid · Faza 0: recunoaștere", style = MaterialTheme.typography.titleLarge)
        Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            Button(onClick = ::run, enabled = !running) { Text("Rulează") }
            OutlinedButton(onClick = { activity.share(report) }, enabled = !running && report.isNotEmpty()) {
                Text("Trimite raportul")
            }
        }
        SelectionContainer(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())) {
            Text(report, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}
