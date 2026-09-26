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
        if ("arm64-v8a" in Build.SUPPORTED_ABIS) Item(Level.OK, "Procesor pe 64 de biți", "arm64-v8a")
        else Item(Level.ERROR, "Procesor pe 64 de biți", "Lipsește arm64-v8a (${Build.SUPPORTED_ABIS.joinToString()}): FEX rulează doar pe ARM64.")

    private fun pageSize(): Item {
        val page = Os.sysconf(OsConstants._SC_PAGESIZE)
        return if (page == 4096L) Item(Level.OK, "Pagini de memorie", "4 KB")
        else Item(Level.ERROR, "Pagini de memorie", "${page / 1024} KB: FEX funcționează doar cu pagini de 4 KB (NOTES N-001).")
    }

    /** FEX is built with -march=armv8.2-a (scripts/build-fex.sh): LSE atomics and FP16 must be present. */
    private fun cpu(): Item {
        val features = Recon.readFile("/proc/cpuinfo").lines()
            .firstOrNull { it.startsWith("Features") }?.substringAfter(':')?.trim()?.split(' ')?.toSet().orEmpty()
        val missing = listOf("atomics", "asimdhp", "fphp").filter { it !in features }
        return when {
            features.isEmpty() -> Item(Level.INFO, "Procesor ARMv8.2", "Nu pot citi /proc/cpuinfo.")
            missing.isEmpty() -> Item(Level.OK, "Procesor ARMv8.2", buildString {
                append("atomics, fp16")
                if ("lrcpc" in features) append(", rcpc")
                if ("uscat" in features) append(", lse2")
            })
            else -> Item(Level.ERROR, "Procesor ARMv8.2",
                "Lipsesc ${missing.joinToString()}: build-ul FEX actual cere ARMv8.2 (Snapdragon 845 sau mai nou).")
        }
    }

    private val adrenoRegex = Regex("Adreno\\D{0,8}(\\d)(\\d{2})", RegexOption.IGNORE_CASE)

    /** Turnip (Mesa) drives Adreno 6xx/7xx/8xx through /dev/kgsl-3d0; nothing else has a Linux Vulkan driver here. */
    private fun gpu(): Item {
        val title = "Placă video (Turnip)"
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
                else -> "necunoscută (egl=$egl, vulkan=$vk)"
            }
            return Item(Level.ERROR, title,
                "GPU $what fără /dev/kgsl-3d0. Turnip merge doar pe Adreno (Snapdragon); pentru alte GPU-uri nu există încă un driver Vulkan Linux utilizabil din aplicație.")
        }
        if (m == null) return Item(Level.INFO, title, "Adreno (model necunoscut: \"$sysfs\"). Rulează Avansat › Recunoaștere pentru detalii.")
        val series = m.groupValues[1].toInt()
        val model = "${m.groupValues[1]}${m.groupValues[2]}"
        return when {
            model == "660" -> Item(Level.OK, title, "Adreno $model — verificat (Realme GT, Dota 2 la meniu).")
            series in 6..8 -> Item(Level.OK, title, "Adreno $model — suportat de Turnip; netestat încă în fexdroid.")
            else -> Item(Level.ERROR, title, "Adreno $model: Turnip suportă doar seriile 6xx, 7xx și 8xx.")
        }
    }

    private fun kernel(): Item {
        val release = Os.uname().release
        val (major, minor) = Regex("^(\\d+)\\.(\\d+)").find(release)?.destructured?.let { (a, b) -> a.toInt() to b.toInt() }
            ?: return Item(Level.INFO, "Kernel", release)
        val v = major * 100 + minor
        return when {
            v >= 515 -> Item(Level.OK, "Kernel", release)
            v >= 504 -> Item(Level.OK, "Kernel", "$release (FEX recomandă 5.15+; 5.4 e verificat pe Realme GT)")
            else -> Item(Level.INFO, "Kernel", "$release: mai vechi decât 5.4, netestat. FEX poate avea probleme (recomandă 5.15+).")
        }
    }

    private fun android(ctx: Context): Item {
        val target = ctx.applicationInfo.targetSdkVersion
        return when {
            target > 28 -> Item(Level.ERROR, "Varianta aplicației",
                "Varianta „modern” (targetSdk $target) nu poate executa programe din datele aplicației (NOTES N-010). Instalează varianta „legacy”.")
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> Item(Level.INFO, "Android",
                "${Build.VERSION.RELEASE}: mai vechi decât Android 12, netestat.")
            Build.MANUFACTURER.equals("samsung", ignoreCase = true) -> Item(Level.INFO, "Android",
                "${Build.VERSION.RELEASE} (Samsung): politica seccomp poate diferi de AOSP; netestat încă.")
            else -> Item(Level.OK, "Android", Build.VERSION.RELEASE)
        }
    }

    private fun memory(ctx: Context): Item {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val gib = (mi.totalMem + (1L shl 29)) shr 30
        return when {
            gib >= 8 -> Item(Level.OK, "Memorie RAM", "$gib GB")
            gib >= 6 -> Item(Level.INFO, "Memorie RAM", "$gib GB: jocurile mari (Dota 2 folosește 2–4 GB) pot fi închise de Android.")
            else -> Item(Level.INFO, "Memorie RAM", "$gib GB: puțin pentru Steam și jocuri; merg doar programe mici.")
        }
    }

    private fun storage(ctx: Context): Item {
        val free = StatFs(ctx.filesDir.path).availableBytes shr 30
        return if (free >= 20) Item(Level.OK, "Spațiu liber", "$free GB")
        else Item(Level.INFO, "Spațiu liber", "$free GB: mediul Linux și Steam au nevoie de ~5 GB, jocurile de zeci de GB.")
    }

    /** Android 12+ kills "phantom" child processes beyond a limit (32); Steam starts many. */
    private fun phantomProcesses(): Item {
        val title = "Limita de procese copil"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Item(Level.OK, title, "Nu există înainte de Android 12.")
        val max = Recon.runCmd("/system/bin/device_config", "get", "activity_manager", "max_phantom_processes").trim().toIntOrNull()
        return if (max != null && max >= 1024) Item(Level.OK, title, "Dezactivată ($max)")
        else Item(Level.INFO, title,
            "Android poate opri Steam (limită ${max ?: 32}). Din Opțiuni dezvoltator › „Dezactivează restricțiile pentru procesele copil” " +
                "sau pe PC: adb shell device_config put activity_manager max_phantom_processes 2147483647")
    }
}
