package ro.cobrabm.fexdroid

import android.app.ActivityManager
import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import android.view.InputDevice
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 0 recon, run from inside the app sandbox (untrusted_app domain), which is
 * exactly where FEX will run. Mirrors scripts/phone-recon.sh but adds probes that
 * only make sense in-app: seccomp behaviour, W^X policy for this targetSdk, KGSL.
 */
class Recon(private val ctx: Context) {

    fun sections(): List<Pair<String, () -> String>> = listOf(
        "identity" to ::identity,
        "memory / page size" to ::memory,
        "kernel" to { readFile("/proc/version") },
        "cpu" to ::cpu,
        "sandbox" to ::sandbox,
        "syscalls under app seccomp" to { ReconNative.syscalls() },
        "W^X (targetSdk ${ctx.applicationInfo.targetSdkVersion})" to {
            ReconNative.wx(ctx.filesDir.absolutePath, ctx.applicationInfo.nativeLibraryDir)
        },
        "KGSL" to { ReconNative.kgsl() + gpuSysfs() },
        "Vulkan (vendor driver)" to { ReconNative.vulkan() },
        "input devices" to ::inputDevices,
        "phantom process killer" to ::phantomProcs,
        "thermal" to ::thermal,
    )

    fun header(): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return "fexdroid recon ${pi.versionName} (${ctx.packageName}) at $ts\n"
    }

    private fun identity(): String = buildString {
        appendLine("model            ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("android          ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("build            ${Build.DISPLAY}")
        appendLine("security patch   ${Build.VERSION.SECURITY_PATCH}")
        appendLine("soc              ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        appendLine("board/hardware   ${Build.BOARD} / ${Build.HARDWARE}")
        appendLine("abis             ${Build.SUPPORTED_ABIS.joinToString()}")
        for (p in listOf("ro.hardware.vulkan", "ro.hardware.egl", "ro.board.platform",
            "ro.product.cpu.pagesize.max", "ro.boot.hardware.cpu.pagesize", "ro.vendor.api_level")) {
            appendLine("%-32s %s".format(p, getprop(p)))
        }
    }

    private fun memory(): String = buildString {
        val page = Os.sysconf(OsConstants._SC_PAGESIZE)
        val verdict = if (page == 4096L) "OK for FEX" else "FEX BLOCKER (needs 4 KiB pages)"
        appendLine("page size        $page bytes -> $verdict")
        append(ReconNative.va())
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        appendLine("RAM total/avail  ${mi.totalMem shr 20} / ${mi.availMem shr 20} MiB")
        val stat = android.os.StatFs(ctx.filesDir.absolutePath)
        appendLine("data free        ${stat.availableBytes shr 30} GiB")
    }

    private fun cpu(): String = buildString {
        val info = readFile("/proc/cpuinfo")
        val parts = info.lines().filter { it.startsWith("CPU part") }.groupingBy { it }.eachCount()
        parts.forEach { (k, v) -> appendLine("$v x $k") }
        info.lines().firstOrNull { it.startsWith("Features") }?.let { appendLine(it) }
        File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }
            ?.sortedBy { it.name.removePrefix("cpu").toInt() }
            ?.forEach { c ->
                val max = readFile("${c.path}/cpufreq/cpuinfo_max_freq").trim()
                val cluster = readFile("${c.path}/topology/cluster_id").trim()
                appendLine("${c.name}: max ${max} kHz cluster $cluster")
            }
    }

    private fun sandbox(): String = buildString {
        appendLine("selinux context  ${readFile("/proc/self/attr/current").trim('\u0000', '\n')}")
        readFile("/proc/self/status").lines()
            .filter { it.startsWith("Seccomp") || it.startsWith("NoNewPrivs") }
            .forEach { appendLine(it) }
        appendLine("uid              ${Os.getuid()}")
        appendLine("filesDir         ${ctx.filesDir}")
        appendLine("nativeLibraryDir ${ctx.applicationInfo.nativeLibraryDir}")
        appendLine("sysvipc in /proc ${File("/proc/sysvipc").exists()}")
        appendLine("/dev/ashmem      ${File("/dev/ashmem").exists()}")
    }

    private fun gpuSysfs(): String = buildString {
        for (f in listOf("gpu_model", "gpu_chipid", "max_gpuclk", "gpubusy")) {
            appendLine("sysfs kgsl/$f: ${readFile("/sys/class/kgsl/kgsl-3d0/$f").trim()}")
        }
    }

    private fun inputDevices(): String = buildString {
        val im = ctx.getSystemService(Context.INPUT_SERVICE) as InputManager
        for (id in im.inputDeviceIds) {
            val d = im.getInputDevice(id) ?: continue
            val kinds = buildList {
                if (d.supportsSource(InputDevice.SOURCE_KEYBOARD) &&
                    d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) add("keyboard")
                if (d.supportsSource(InputDevice.SOURCE_MOUSE)) add("mouse")
                if (d.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) add("touch")
                if (d.supportsSource(InputDevice.SOURCE_GAMEPAD)) add("gamepad")
            }
            appendLine("${d.name} ext=${d.isExternal} ${kinds.joinToString()}")
        }
    }

    private fun phantomProcs(): String = buildString {
        for (k in listOf("settings_enable_monitor_phantom_procs")) {
            val v = runCatching { Settings.Global.getString(ctx.contentResolver, k) }.getOrElse { "unreadable: ${it.message}" }
            appendLine("$k = $v")
        }
        appendLine("device_config max_phantom_processes = ${
            runCmd("/system/bin/device_config", "get", "activity_manager", "max_phantom_processes").trim()}")
    }

    private fun thermal(): String = buildString {
        File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
            ?.sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: 0 }
            ?.take(60)
            ?.forEach { z ->
                appendLine("${readFile("${z.path}/type").trim()} = ${readFile("${z.path}/temp").trim()}")
            }
    }

    companion object {
        fun readFile(path: String): String =
            runCatching { File(path).readText() }.getOrElse { "<${it.javaClass.simpleName}: ${it.message}>" }

        fun runCmd(vararg cmd: String): String = runCatching {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        }.getOrElse { "<${it.message}>" }

        fun getprop(name: String): String = runCmd("/system/bin/getprop", name).trim()
    }
}
