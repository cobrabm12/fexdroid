package ro.cobrabm.fexdroid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Acasă: what is installed, and the two main actions (Steam / Dota 2 direct). */
@Composable
fun HomeScreen(onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val env = remember { LinuxEnv(ctx) }
    var status by remember { mutableStateOf<InstallStatus?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { refresh++; onPauseOrDispose { } }
    LaunchedEffect(refresh, GameSession.state) {
        status = withContext(Dispatchers.IO) { runCatching { InstallStatus.check(env) }.getOrNull() }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                Text("fexdroid", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("Jocuri Linux pentru PC, direct pe telefon",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (GameSession.active) RunningCard()

            StatusCard(status, env)

            val s = status
            val ready = s != null && s.payloadProblem == null
            val steamOk = ready && s.steamRootfs
            val dotaOk = ready && s.dota
            val steamAction: @Composable (Boolean) -> Unit = { primary ->
                BigAction(
                    primary = primary, icon = AppIcons.Play, title = "Pornește Steam",
                    subtitle = when {
                        s == null -> "Verific instalarea…"
                        !steamOk -> "Lipsesc bibliotecile Steam — vezi Setări"
                        !s.steamClient -> "Prima pornire descarcă clientul Steam de la Valve"
                        else -> "Autentificare cu cod QR din aplicația Steam"
                    },
                    enabled = steamOk,
                ) { GameSession.start(ctx, Game.STEAM) }
            }
            val dotaAction: @Composable (Boolean) -> Unit = { primary ->
                BigAction(
                    primary = primary, icon = AppIcons.Gamepad, title = "Pornește Dota 2 (direct)",
                    subtitle = if (dotaOk) "Fără clientul Steam: fără joc online, doar test" else "Dota 2 nu e instalat — vezi Setări",
                    enabled = dotaOk,
                ) { GameSession.start(ctx, Game.DOTA) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (AppSettings.gameWithoutSteam) { dotaAction(true); steamAction(false) }
                else { steamAction(true); dotaAction(false) }
            }
            if (s != null && (!steamOk || !s.dota)) {
                TextButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Icon(AppIcons.Info, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Cum instalez Steam și jocurile?")
                }
            }
        }
    }
}

@Composable
private fun RunningCard() {
    val st = GameSession.state
    val (title, detail) = when (st) {
        is SessionState.Starting -> "${st.game.title} pornește…" to st.step.label
        is SessionState.Running -> "${st.game.title} rulează" to "Rezoluție ${GameSession.resolution.label}"
        is SessionState.Exited -> "${st.game.title} s-a închis" to "Cod de ieșire ${st.code}"
        is SessionState.Failed -> "${st.game.title} nu a pornit" to st.message
        SessionState.Idle -> return
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (st is SessionState.Starting) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                else Icon(AppIcons.Gamepad, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { GameSession.playerVisible = true }) {
                    Icon(AppIcons.Play, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Revino la joc")
                }
                OutlinedButton(onClick = { GameSession.stop() }) {
                    Icon(AppIcons.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Oprește")
                }
            }
        }
    }
}

@Composable
private fun StatusCard(s: InstallStatus?, env: LinuxEnv) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text("Stare", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (s == null) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp)); Text("Verific…")
                }
                return@Column
            }
            StatusRow(
                if (s.payloadProblem != null) Level.ERROR else if (s.payloadReady) Level.OK else Level.INFO,
                "Mediul Linux",
                s.payloadProblem ?: if (s.payloadReady) "Instalat (${env.installedVersion()})"
                else "Se instalează automat la prima pornire (1–2 minute)",
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            StatusRow(if (s.steamRootfs) Level.OK else Level.ERROR, "Biblioteci Steam (x86)",
                if (s.steamRootfs) "Instalate" else "Lipsesc — necesare pentru Steam")
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            StatusRow(if (s.steamClient) Level.OK else Level.INFO, "Client Steam",
                if (s.steamClient) "Prezent" else "Se descarcă la prima pornire a Steam")
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            StatusRow(if (s.dota) Level.OK else Level.INFO, "Dota 2",
                if (s.dota) "Instalat" else "Neinstalat")
        }
    }
}

enum class Level { OK, INFO, ERROR }

@Composable
fun StatusRow(level: Level, title: String, detail: String) {
    val (icon, tint) = when (level) {
        Level.OK -> AppIcons.CheckCircle to MaterialTheme.colorScheme.primary
        Level.INFO -> AppIcons.Info to MaterialTheme.colorScheme.tertiary
        Level.ERROR -> AppIcons.Error to MaterialTheme.colorScheme.error
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BigAction(
    primary: Boolean, icon: ImageVector, title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val padding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
    val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(32.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Unspecified)
            }
        }
    }
    val mod = Modifier.fillMaxWidth().heightIn(min = 80.dp)
    if (primary) Button(onClick, mod, enabled = enabled, shape = shape, contentPadding = padding) { content() }
    else FilledTonalButton(onClick, mod, enabled = enabled, shape = shape, contentPadding = padding,
        colors = ButtonDefaults.filledTonalButtonColors()) { content() }
}
