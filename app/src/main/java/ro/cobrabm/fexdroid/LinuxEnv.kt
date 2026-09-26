package ro.cobrabm.fexdroid

import android.content.Context
import android.net.ConnectivityManager
import android.system.Os
import org.json.JSONObject
import java.io.File

/**
 * The on-device Linux environment: installs the rootfs payload from assets and
 * starts glibc processes inside it (PLAN.md phases 1 and 2).
 *
 * Layout under filesDir (must match scripts/config.sh, it is baked into glibc):
 *   rootfs/   Debian arm64 + patched glibc + FEX   (host side)
 *   x86_64/   Debian amd64                         (FEX guest RootFS)
 */
class LinuxEnv(private val ctx: Context) {
    companion object {
        /** One installer at a time across all screens/threads (see NOTES.md N-021). */
        private val installLock = Any()
    }

    val files: File = ctx.filesDir
    val root = File(files, "rootfs")
    /** Base x86_64 guest rootfs from the APK. */
    val x86Base = File(files, "x86_64")
    /** Phase 5: bigger guest rootfs with Steam's amd64+i386 libraries, installed from a file. */
    val x86Steam = File(files, "x86_64-steam")
    val x86Root: File get() = if (File(x86Steam, ".complete").exists()) x86Steam else x86Base
    /** User data (HOME, Steam, FEX caches) lives outside the rootfs so payload updates keep it. */
    val home = File(files, "home/user")
    /** Game library copied or downloaded here (Steam library folder). */
    val steamLibrary = File(files, "steamlib")
    private val versionFile = File(files, ".payload.version")

    /** Paths the payload was built for (assets/payload.json). */
    val expected: JSONObject? = runCatching {
        JSONObject(ctx.assets.open("payload.json").bufferedReader().readText())
    }.getOrNull()

    fun payloadPresent() = expected != null

    /** glibc has the rootfs path compiled in; this app's filesDir must match it. */
    fun pathProblem(): String? {
        val want = expected?.optString("root") ?: return "APK fără payload (rulează scripts/build-payload.sh)"
        val have = File("/data/data/${ctx.packageName}/files/rootfs")
        return if (have.path != want)
            "Payload construit pentru $want, dar aplicația folosește ${have.path}. " +
                "Faza 1 merge doar pe varianta legacy (sau reconstruiește cu FXD_PACKAGE=${ctx.packageName})."
        else null
    }

    fun installedVersion(): String? = versionFile.takeIf { it.exists() }?.readText()?.trim()
    fun payloadVersion(): String? = runCatching {
        ctx.assets.open("payload.version").bufferedReader().readText().trim()
    }.getOrNull()

    fun needsInstall() = payloadPresent() && installedVersion() != payloadVersion()

    /** Installs the payload if needed; safe to call from several threads. Returns false on path mismatch. */
    fun ensureInstalled(log: (String) -> Unit): Boolean = synchronized(installLock) {
        pathProblem()?.let { log(it); return false }
        if (needsInstall()) install(log) else writeIdentityFiles()
        true
    }

    fun install(log: (String) -> Unit) = synchronized(installLock) { installLocked(log) }

    private fun installLocked(log: (String) -> Unit) {
        versionFile.delete()
        for ((asset, dir) in listOf("rootfs-arm64.tar" to root, "rootfs-x86_64.tar" to x86Base)) {
            log("Șterg $dir …")
            dir.deleteRecursively()
            dir.mkdirs()
            log("Extrag $asset …")
            val t0 = System.nanoTime()
            val tx = TarExtractor(dir) { bytes -> log("  $asset: ${bytes shr 20} MiB") }
            ctx.assets.open(asset).buffered(1 shl 16).use { tx.extract(it) }
            log("  gata: ${tx.files} fișiere, ${tx.symlinks} symlink-uri, ${tx.hardlinks} hardlink-uri " +
                "în ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
        writeIdentityFiles()
        versionFile.writeText(payloadVersion() ?: "")
        log("Payload instalat.")
    }

    /** /etc files that depend on this device/user (glibc reads them from rootfs/etc). */
    fun writeIdentityFiles() {
        val uid = Os.getuid()
        val gid = Os.getgid()
        home.mkdirs()
        val dns = runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            // Scoped IPv6 (fe80::...%wlan0) does not parse in resolv.conf; glibc uses 3 at most.
            cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.mapNotNull { it.hostAddress }
                ?.filter { '%' !in it }?.take(3)
        }.getOrNull().orEmpty().ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        val resolv = dns.joinToString("") { "nameserver $it\n" }
        File(root, "etc/passwd").writeText(
            "root:x:0:0:root:/root:/bin/sh\nuser:x:$uid:$gid:fexdroid:${home.path}:${root.path}/bin/sh\n")
        File(root, "etc/group").writeText("root:x:0:\nuser:x:$gid:\n")
        File(root, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(root, "etc/resolv.conf").writeText(resolv)
        // x86 trees: FEX serves a guest /etc file from the tree when it exists there, else the
        // Android one, and Android has no resolv.conf (Steam: "http error 0", NOTES.md N-028).
        for (tree in guestTrees) {
            if (!File(tree, "etc").isDirectory) continue
            runCatching {
                File(tree, "etc/resolv.conf").run { delete(); writeText(resolv) } // may be a symlink
                File(tree, "etc/hosts").run { delete(); writeText("127.0.0.1 localhost\n::1 localhost\n") }
                // FEX opens /proc and /sys inside the tree when the tree has them, and Docker/OCI
                // exports ship both as empty dirs. openat(that fd, "self/task") then fails, which
                // kills Chromium's zygote (steamwebhelper). delete() only removes empty dirs.
                File(tree, "proc").delete(); File(tree, "sys").delete()
            }
        }
    }

    /** x86_64 guest rootfs trees FEX may run with (FEX_ROOTFS): base, Steam, game (sniper). */
    val guestTrees: List<File> get() = listOf(x86Base, x86Steam, File(files, "sniper-rootfs"))

    /** Adreno (KGSL) -> Turnip; anything else -> lavapipe, Vulkan on the CPU (NOTES.md N-027). */
    val hasTurnipGpu: Boolean get() = File("/dev/kgsl-3d0").exists()

    /** Vulkan driver manifest for the arm64 loader (and FEX's host-side Vulkan thunk). */
    fun vulkanIcd(): String {
        val icdDir = File(root, "usr/share/vulkan/icd.d")
        val turnip = File(icdDir, "freedreno_icd.aarch64.json")
        if (hasTurnipGpu) return turnip.path
        return icdDir.listFiles { f -> f.name.startsWith("lvp_icd") }?.firstOrNull()?.path ?: turnip.path
    }

    fun environment(): Map<String, String> {
        home.mkdirs()
        val icd = vulkanIcd()
        return mapOf(
            "PATH" to "${root.path}/usr/local/bin:${root.path}/usr/bin:${root.path}/bin",
            "HOME" to home.path,
            "USER" to "user",
            "LANG" to "C.UTF-8",
            "TMPDIR" to "${root.path}/tmp",
            "XDG_RUNTIME_DIR" to "${root.path}/tmp",
            "FXD_ROOT" to root.path,
            "FXD_FILES" to files.path,
            // Vulkan loader (arm64, also used by FEX's host-side Vulkan thunk): exactly one driver.
            "VK_ICD_FILENAMES" to icd,
            "VK_DRIVER_FILES" to icd,
            // FEX: guest rootfs and config/cache location.
            "FEX_ROOTFS" to x86Root.path,
            "FEX_APP_CONFIG_LOCATION" to "${home.path}/.fex-emu/",
            "FEX_APP_DATA_LOCATION" to "${home.path}/.fex-emu/",
            // JIT disk cache (FexConfig): outside HOME-dependent defaults, so Steam and the
            // direct Dota start (different HOME) share one cache.
            "FEX_APP_CACHE_LOCATION" to "${home.path}/.cache/fex-emu/",
        )
    }

    /**
     * Starts [argv] (argv[0] = absolute path inside the rootfs) with the Linux
     * environment, merging stderr into stdout. Lines are delivered to [onLine].
     */
    fun run(argv: List<String>, onLine: (String) -> Unit): Int {
        val pb = ProcessBuilder(argv).redirectErrorStream(true).directory(home.apply { mkdirs() })
        pb.environment().apply { clear(); putAll(environment()) }
        val p = pb.start()
        p.outputStream.close()
        p.inputStream.bufferedReader().forEachLine(onLine)
        return p.waitFor()
    }

    fun sh(command: String, onLine: (String) -> Unit) = run(listOf("${root.path}/bin/sh", "-c", command), onLine)
}
