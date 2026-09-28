package ro.cobrabm.fexdroid

import android.util.Log
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Test steps for phases 1 and 2, runnable from the UI or via `--es action <id>` over adb. */
object LinuxSteps {
    fun phase1(env: LinuxEnv) = listOf("${env.root}/bin/sh", "-c", "uname -a; ls /; echo; echo \"rootfs:\"; ls \$FXD_ROOT")
    // The test programs are in the base x86 tree only, not in Steam's (env.x86Root once Steam's
    // libraries are installed): run them there.
    private fun fexTest(env: LinuxEnv, name: String) = listOf("${env.root}/bin/sh", "-c",
        "FEX_ROOTFS='${env.x86Base}' exec '${env.root}/usr/bin/FEX' '${env.x86Base}/opt/fexdroid-tests/$name'")
    fun fexStatic(env: LinuxEnv) = fexTest(env, "hello-static")
    fun fexDynamic(env: LinuxEnv) = fexTest(env, "hello-dynamic")
    // Phase 3, step 1: native arm64 vulkaninfo against Turnip.
    fun vulkaninfo(env: LinuxEnv) = listOf("${env.root}/usr/bin/vulkaninfo", "--summary")
    // Phase 3, step 1b: x86_64 vulkaninfo through FEX + Vulkan thunk.
    fun fexVulkaninfo(env: LinuxEnv) = listOf("${env.root}/usr/bin/FEX", "${env.x86Root}/usr/bin/vulkaninfo", "--summary")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinuxScreen(autoAction: String?, autoCmd: String? = null) {
    val ctx = LocalContext.current
    val env = remember { LinuxEnv(ctx) }
    val scope = rememberCoroutineScope()
    var log by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("uname -a; cat /proc/cpuinfo | head -5") }
    val logFile = File(ctx.getExternalFilesDir(null), "linux.txt")

    fun append(line: String) {
        Log.i("fexdroid-linux", line)
        log += line + "\n"
    }

    fun status(): String = env.pathProblem()
        ?: if (env.needsInstall()) "Payload not installed (version ${env.payloadVersion()})."
        else "Payload installed: ${env.installedVersion()} in ${env.root}"

    fun job(title: String, body: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            append("\n$ $title")
            try { body() } catch (t: Throwable) { append("EXCEPTION: $t") }
            withContext(Dispatchers.IO) { logFile.writeText(log) }
            busy = false
        }
    }

    fun ensureInstalled(): Boolean = env.ensureInstalled(::append)

    fun runArgv(title: String, argv: () -> List<String>) = job(title) {
        withContext(Dispatchers.IO) {
            if (ensureInstalled()) {
                val t0 = System.nanoTime()
                val rc = env.run(argv()) { scope.launch { append(it) } }
                append("[exit $rc, ${(System.nanoTime() - t0) / 1_000_000} ms]")
            }
        }
    }

    LaunchedEffect(autoAction) {
        when (autoAction) {
            // adb debugging: `--es action cmd --es cmd '<shell command>'`, run inside the app sandbox.
            "cmd" -> autoCmd?.let { c -> runArgv(c) { listOf("${env.root}/bin/sh", "-c", c) } }
            "phase1" -> runArgv("phase 1: sh -c 'uname -a; ls /'") { LinuxSteps.phase1(env) }
            "fex-static" -> runArgv("phase 2: FEX hello-static") { LinuxSteps.fexStatic(env) }
            "fex-dynamic" -> runArgv("phase 2: FEX hello-dynamic") { LinuxSteps.fexDynamic(env) }
            "vulkaninfo" -> runArgv("phase 3: vulkaninfo arm64 (Turnip)") { LinuxSteps.vulkaninfo(env) }
            "fex-vulkaninfo" -> runArgv("phase 3: vulkaninfo x86_64 through FEX") { LinuxSteps.fexVulkaninfo(env) }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(status(), style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                job("install the payload") { withContext(Dispatchers.IO) { env.pathProblem()?.let(::append) ?: env.install(::append) } }
            }) { Text("Reinstall the rootfs") }
            Button(enabled = !busy, onClick = { runArgv("phase 1: sh -c 'uname -a; ls /'") { LinuxSteps.phase1(env) } }) {
                Text("Phase 1: uname + ls")
            }
            Button(enabled = !busy, onClick = { runArgv("phase 2: FEX hello-static") { LinuxSteps.fexStatic(env) } }) {
                Text("FEX static")
            }
            Button(enabled = !busy, onClick = { runArgv("phase 2: FEX hello-dynamic") { LinuxSteps.fexDynamic(env) } }) {
                Text("FEX dynamic")
            }
            Button(enabled = !busy, onClick = { runArgv("phase 3: vulkaninfo arm64 (Turnip)") { LinuxSteps.vulkaninfo(env) } }) {
                Text("vulkaninfo (Turnip)")
            }
            Button(enabled = !busy, onClick = { runArgv("phase 3: vulkaninfo x86_64 through FEX") { LinuxSteps.fexVulkaninfo(env) } }) {
                Text("vulkaninfo x86 (FEX)")
            }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(command, { command = it }, Modifier.weight(1f), singleLine = true,
                label = { Text("sh command") })
            Button(enabled = !busy, modifier = Modifier.padding(start = 8.dp),
                onClick = { val c = command; runArgv(c) { listOf("${env.root}/bin/sh", "-c", c) } }) { Text("Run") }
        }
        SelectionContainer(Modifier.fillMaxSize().padding(top = 6.dp).verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())) {
            Text(log, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}
