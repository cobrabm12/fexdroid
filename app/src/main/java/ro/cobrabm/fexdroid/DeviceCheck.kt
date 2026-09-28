package ro.cobrabm.fexdroid

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Can this phone run fexdroid? A short verdict per requirement, for testers on devices
 * other than the ones in NOTES.md (Realme GT / SD888 verified, N-021..N-024).
 * Uses the same sources as the recon screen, without the slow probes.
 */
object DeviceCheck {
    data class Item(val level: Level, val title: String, val detail: String)

    /** Worst level wins: any ERROR means the Linux environment cannot run here. */
    fun verdict(items: List<Item>): Level = when {
        items.any { it.level == Level.ERROR } -> Level.ERROR
        items.any { it.level == Level.INFO } -> Level.INFO
        else -> Level.OK
    }

    /** Does file I/O and starts small helper processes: call off the main thread. */
    fun run(ctx: Context): List<Item> = listOf(
        abi(), pageSize(), cpu(), gpu(), kernel(), android(ctx), memory(ctx), storage(ctx), phantomProcesses(),
    )

    fun report(items: List<Item>): String = buildString {
        appendLine("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE}")
        for (i in items) appendLine("[${i.level}] ${i.title}: ${i.detail}")
    }

    private fun abi(): Item =
        if ("arm64-v8a" in Build.SUPPORTED_ABIS) Item(Level.OK, str(R.string.check_abi), "arm64-v8a")
        else Item(Level.ERROR, str(R.string.check_abi), str(R.string.check_abi_missing, Build.SUPPORTED_ABIS.joinToString()))

    private fun pageSize(): Item {
        val page = Os.sysconf(OsConstants._SC_PAGESIZE)
        return if (page == 4096L) Item(Level.OK, str(R.string.check_pages), "4 KB")
        else Item(Level.ERROR, str(R.string.check_pages), str(R.string.check_pages_bad, page / 1024))
    }

    /** FEX is built with -march=armv8.2-a (scripts/build-fex.sh): LSE atomics and FP16 must be present. */
    private fun cpu(): Item {
        val features = Recon.readFile("/proc/cpuinfo").lines()
            .firstOrNull { it.startsWith("Features") }?.substringAfter(':')?.trim()?.split(' ')?.toSet().orEmpty()
        val missing = listOf("atomics", "asimdhp", "fphp").filter { it !in features }
        return when {
            features.isEmpty() -> Item(Level.INFO, str(R.string.check_cpu), str(R.string.check_cpu_unreadable))
            missing.isEmpty() -> Item(Level.OK, str(R.string.check_cpu), buildString {
                append("atomics, fp16")
                if ("lrcpc" in features) append(", rcpc")
                if ("uscat" in features) append(", lse2")
            })
            else -> Item(Level.ERROR, str(R.string.check_cpu),
                str(R.string.check_cpu_missing, missing.joinToString()))
        }
    }

    private val adrenoRegex = Regex("Adreno\\D{0,8}(\\d)(\\d{2})", RegexOption.IGNORE_CASE)

    /** Turnip (Mesa) drives Adreno 6xx/7xx/8xx through /dev/kgsl-3d0; nothing else has a Linux Vulkan driver here. */
    private fun gpu(): Item {
        val title = str(R.string.check_gpu)
        val kgsl = File("/dev/kgsl-3d0").exists()
        val sysfs = Recon.readFile("/sys/class/kgsl/kgsl-3d0/gpu_model").trim()
        val egl = Recon.getprop("ro.hardware.egl")
        val vk = Recon.getprop("ro.hardware.vulkan")
        val hint = listOf(sysfs, egl, vk, Build.HARDWARE, Build.BOARD).joinToString(" ")
        val m = adrenoRegex.find(sysfs) ?: adrenoRegex.find(hint)
        if (!kgsl) {
            val what = when {
                Regex("mali", RegexOption.IGNORE_CASE).containsMatchIn(hint) -> "Mali (Exynos / Dimensity / Tensor)"
                Regex("xclipse|samsung", RegexOption.IGNORE_CASE).containsMatchIn(hint) -> "Xclipse (Exynos)"
                Regex("powervr|\\bimg\\b", RegexOption.IGNORE_CASE).containsMatchIn(hint) -> "PowerVR"
                else -> str(R.string.check_gpu_unknown, egl, vk)
            }
            return Item(Level.INFO, title,
                str(R.string.check_gpu_no_turnip, what))
        }
        if (m == null) return Item(Level.INFO, title, str(R.string.check_gpu_adreno_unknown, sysfs))
        val series = m.groupValues[1].toInt()
        val model = "${m.groupValues[1]}${m.groupValues[2]}"
        return when {
            model == "660" -> Item(Level.OK, title, str(R.string.check_gpu_verified_660, model))
            model == "840" -> Item(Level.OK, title, str(R.string.check_gpu_verified_840, model))
            series in 6..8 -> Item(Level.OK, title, str(R.string.check_gpu_supported, model))
            else -> Item(Level.ERROR, title, str(R.string.check_gpu_unsupported, model))
        }
    }

    private fun kernel(): Item {
        val release = Os.uname().release
        val (major, minor) = Regex("^(\\d+)\\.(\\d+)").find(release)?.destructured?.let { (a, b) -> a.toInt() to b.toInt() }
            ?: return Item(Level.INFO, "Kernel", release)
        val v = major * 100 + minor
        return when {
            v >= 515 -> Item(Level.OK, "Kernel", release)
            v >= 504 -> Item(Level.OK, "Kernel", str(R.string.check_kernel_54, release))
            else -> Item(Level.INFO, "Kernel", str(R.string.check_kernel_old, release))
        }
    }

    private fun android(ctx: Context): Item {
        val target = ctx.applicationInfo.targetSdkVersion
        return when {
            target > 28 -> Item(Level.ERROR, str(R.string.check_variant),
                str(R.string.check_variant_modern, target))
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> Item(Level.INFO, "Android",
                str(R.string.check_android_old, Build.VERSION.RELEASE))
            else -> Item(Level.OK, "Android", Build.VERSION.RELEASE)
        }
    }

    private fun memory(ctx: Context): Item {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val gib = (mi.totalMem + (1L shl 29)) shr 30
        return when {
            // Dota 2 under the emulator: 4.5 GB at its main menu, 7-8 GB after a few matches (NOTES N-042).
            gib >= 15 -> Item(Level.OK, str(R.string.check_memory), "$gib GB")
            gib >= 7 -> Item(Level.INFO, str(R.string.check_memory), str(R.string.check_memory_tight, gib))
            else -> Item(Level.INFO, str(R.string.check_memory), str(R.string.check_memory_low, gib))
        }
    }

    private fun storage(ctx: Context): Item {
        val free = StatFs(ctx.filesDir.path).availableBytes shr 30
        return if (free >= 20) Item(Level.OK, str(R.string.check_storage), "$free GB")
        else Item(Level.INFO, str(R.string.check_storage), str(R.string.check_storage_low, free))
    }

    /** Android 12+ kills "phantom" child processes beyond a limit (32); Steam starts many. */
    private fun phantomProcesses(): Item {
        val title = str(R.string.check_phantom)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Item(Level.OK, title, str(R.string.check_phantom_none))
        val max = Recon.runCmd("/system/bin/device_config", "get", "activity_manager", "max_phantom_processes").trim().toIntOrNull()
        return if (max != null && max >= 1024) Item(Level.OK, title, str(R.string.check_phantom_off, max))
        else Item(Level.INFO, title,
            str(R.string.check_phantom_on, max ?: 32))
    }
}
