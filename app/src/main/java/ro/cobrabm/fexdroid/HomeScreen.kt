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
import androidx.compose.material3.LinearProgressIndicator
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
    LaunchedEffect(refresh, GameSession.state, SteamRootfs.state is SteamRootfs.State.Done) {
        status = withContext(Dispatchers.IO) { runCatching { InstallStatus.check(env) }.getOrNull() }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                Text("fexdroid", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(str(R.string.home_tagline),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            LaunchedEffect(Unit) { if (Updater.state == Updater.State.Idle) Updater.check(ctx, quiet = true) }
            UpdateCard()

            if (GameSession.active) RunningCard()

            StatusCard(status, env)

            CompatibilityCard()

            val s = status
            val ready = s != null && s.payloadProblem == null
            val steamOk = ready && s.steamRootfs
            val dotaOk = ready && s.dota
            val steamAction: @Composable (Boolean) -> Unit = { primary ->
                BigAction(
                    primary = primary, icon = AppIcons.Play, title = str(R.string.home_start_steam),
                    subtitle = when {
                        s == null -> str(R.string.home_checking_install)
                        !steamOk -> str(R.string.home_steam_libs_missing)
                        !s.steamClient -> str(R.string.home_first_start_downloads)
                        else -> str(R.string.home_login_hint)
                    },
                    enabled = steamOk,
                ) { GameSession.start(ctx, Game.STEAM) }
            }
            val dotaAction: @Composable (Boolean) -> Unit = { primary ->
                BigAction(
                    primary = primary, icon = AppIcons.Gamepad, title = str(R.string.home_start_dota),
                    subtitle = if (dotaOk) str(R.string.home_dota_direct_hint) else str(R.string.home_dota_missing),
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
                    Text(str(R.string.home_how_to_install))
                }
            }
        }
    }
}

/** Shown only when there is something to say: a newer build, its download, a failure. */
@Composable
private fun UpdateCard() {
    val ctx = LocalContext.current
    val st = Updater.state
    val info = when (st) {
        is Updater.State.Available -> st.info
        is Updater.State.Downloading -> st.info
        is Updater.State.Confirming -> st.info
        is Updater.State.Failed -> st.info ?: return
        else -> return
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (info.version.isEmpty()) str(R.string.update_available) else str(R.string.update_available_version, info.version),
                style = MaterialTheme.typography.titleMedium)
            Text(str(R.string.update_built, info.built.take(16).replace('T', ' '), info.size shr 20),
                style = MaterialTheme.typography.bodySmall)
            when (st) {
                is Updater.State.Downloading -> {
                    LinearProgressIndicator(progress = { st.doneBytes.toFloat() / info.size.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth())
                    Text(str(R.string.update_downloading, st.doneBytes shr 20, info.size shr 20), style = MaterialTheme.typography.bodySmall)
                }
                is Updater.State.Confirming -> Text(str(R.string.update_confirm), style = MaterialTheme.typography.bodySmall)
                else -> {
                    if (st is Updater.State.Failed) Text(st.message, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    if (GameSession.active) Text(str(R.string.update_stop_game_first), style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !GameSession.active, onClick = { Updater.install(ctx, info) }) { Text(str(R.string.update_button)) }
                }
            }
        }
    }
}

@Composable
private fun RunningCard() {
    val st = GameSession.state
    val (title, detail) = when (st) {
        is SessionState.Starting -> str(R.string.session_starting, st.game.title) to st.step.label
        is SessionState.Running -> str(R.string.session_running, st.game.title) to str(R.string.session_resolution, GameSession.resolution.label)
        is SessionState.Exited -> str(R.string.session_closed, st.game.title) to str(R.string.session_exit_code, st.code)
        is SessionState.Failed -> str(R.string.session_did_not_start, st.game.title) to st.message
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
                    Icon(AppIcons.Play, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(str(R.string.home_back_to_game))
                }
                OutlinedButton(onClick = { GameSession.stop() }) {
                    Icon(AppIcons.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(str(R.string.stop))
                }
            }
        }
    }
}

@Composable
private fun StatusCard(s: InstallStatus?, env: LinuxEnv) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(str(R.string.home_status), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (s == null) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp)); Text(str(R.string.home_checking))
                }
                return@Column
            }
            StatusRow(
                if (s.payloadProblem != null) Level.ERROR else if (s.payloadReady) Level.OK else Level.INFO,
                str(R.string.home_linux_env),
                s.payloadProblem ?: if (s.payloadReady) str(R.string.home_installed_version, env.installedVersion())
                else str(R.string.home_installs_at_first_start),
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            StatusRow(if (s.steamRootfs) Level.OK else Level.ERROR, str(R.string.home_steam_libs),
                if (s.steamRootfs) str(R.string.home_installed_plural) else str(R.string.home_missing_needed))
            if (!s.steamRootfs || SteamRootfs.state !is SteamRootfs.State.Idle) SteamRootfsInstall()
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            StatusRow(if (s.steamClient) Level.OK else Level.INFO, str(R.string.home_steam_client),
                if (s.steamClient) str(R.string.home_present) else str(R.string.home_downloads_at_first_start))
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            StatusRow(if (s.gameRootfs) Level.OK else Level.INFO, str(R.string.home_game_env),
                if (s.gameRootfs) str(R.string.home_ready) else str(R.string.home_game_env_later))
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            StatusRow(if (s.dota) Level.OK else Level.INFO, "Dota 2",
                if (s.dota) str(R.string.home_installed) else str(R.string.home_not_installed))
        }
    }
}

/** Download + install of the Steam x86 rootfs from the GitHub release (SteamRootfs). */
@Composable
private fun SteamRootfsInstall() {
    val ctx = LocalContext.current
    val st = SteamRootfs.state
    Column(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (st) {
            is SteamRootfs.State.Working -> {
                val mb = st.doneBytes shr 20
                if (st.totalBytes > 0) {
                    LinearProgressIndicator(progress = { st.doneBytes.toFloat() / st.totalBytes }, modifier = Modifier.fillMaxWidth())
                    Text(str(R.string.install_progress_of, mb, st.totalBytes shr 20), style = MaterialTheme.typography.bodySmall)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(str(R.string.install_progress, mb), style = MaterialTheme.typography.bodySmall)
                }
            }
            is SteamRootfs.State.Failed -> Text(str(R.string.install_failed, st.message), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            SteamRootfs.State.Done -> Text(str(R.string.home_installed_done), style = MaterialTheme.typography.bodySmall)
            SteamRootfs.State.Idle -> Text(str(R.string.home_steam_libs_about),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (st is SteamRootfs.State.Idle || st is SteamRootfs.State.Failed)
            FilledTonalButton(onClick = { SteamRootfs.install(ctx) }) {
                Icon(AppIcons.Play, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(if (st is SteamRootfs.State.Failed) str(R.string.try_again) else str(R.string.home_download_install))
            }
    }
}

/** Device requirements (DeviceCheck): collapsed to one line when everything is fine. */
@Composable
private fun CompatibilityCard() {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<DeviceCheck.Item>?>(null) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        items = withContext(Dispatchers.IO) { runCatching { DeviceCheck.run(ctx) }.getOrNull() }
    }
    val list = items
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(str(R.string.home_compatibility), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (list == null) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp)); Text(str(R.string.home_checking_phone))
                }
                return@Column
            }
            val verdict = DeviceCheck.verdict(list)
            val problems = list.count { it.level != Level.OK }
            StatusRow(verdict, when (verdict) {
                Level.OK -> str(R.string.home_phone_ok)
                Level.INFO -> str(R.string.home_phone_maybe, problems)
                Level.ERROR -> str(R.string.home_phone_no)
            }, "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            if (expanded || verdict != Level.OK) {
                for (i in list.filter { expanded || it.level != Level.OK }) {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    StatusRow(i.level, i.title, i.detail)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) str(R.string.home_less) else str(R.string.home_all_checks)) }
                // The full report: these checks, the emulator self-tests and the last session's log.
                TextButton(onClick = { shareSessionReport(ctx) }) { Text(str(R.string.home_send)) }
            }
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
