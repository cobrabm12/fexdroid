package ro.cobrabm.fexdroid

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * The root filesystem games run in (FEX_ROOTFS for everything Steam starts through
 * SteamLinuxRuntime_sniper, see tools/steam/_v2-entry-point), built on the phone from the
 * runtime Steam downloads itself. Nothing from Valve ships in the APK.
 *
 * Steam's depot holds the platform as `sniper_platform_<version>/files` plus
 * `usr-mtree.txt.gz`: the depot cannot store symlinks, empty files or awkward names, so the
 * mtree lists every entry of /usr and where its content is. pressure-vessel deploys a
 * container from that; containers need user namespaces, so this does the same job into a
 * plain directory. Core libraries (glibc, libstdc++, libgcc) are then replaced with the
 * newer ones from our Debian x86 rootfs, as pressure-vessel does with the host's: FEX's
 * guest thunks need them (scripts/lib/sniper-overrides.py is the PC version of that step).
 */
object GameRootfs {
    private const val TOOL = "steamapps/common/SteamLinuxRuntime_sniper"
    private const val TOOL_APPID = 1628350
    private const val FORMAT = 1
    // Kotlin has no octal literals.
    private val MODE_755 = "755".toInt(8)
    private val MODE_644 = "644".toInt(8)
    private val MODE_1777 = "1777".toInt(8)
    private val EXEC_BITS = "111".toInt(8)

    private val ARCHES = mapOf("x86_64-linux-gnu" to "ld-linux-x86-64.so.2", "i386-linux-gnu" to "ld-linux.so.2")
    private val LIBS = listOf(
        "libc.so.6", "libm.so.6", "libmvec.so.1", "libpthread.so.0", "libdl.so.2", "librt.so.1",
        "libresolv.so.2", "libanl.so.1", "libutil.so.1", "libnss_files.so.2", "libnss_dns.so.2",
        "libc_malloc_debug.so.0", "libstdc++.so.6", "libgcc_s.so.1", "libatomic.so.1",
    )

    fun root(env: LinuxEnv) = File(env.files, "sniper-rootfs")
    /** Written by the entry point when a game starts and there is no rootfs yet. */
    fun request(env: LinuxEnv) = File(env.files, "game-rootfs.request")
    fun ready(env: LinuxEnv) = File(root(env), ".complete").exists()

    /** Newest platform Steam has fully downloaded, or null. */
    fun platform(env: LinuxEnv): File? {
        val manifest = File(env.steamLibrary, "steamapps/appmanifest_$TOOL_APPID.acf")
        // StateFlags 4 = fully installed. Without a manifest (copied by hand) trust the files.
        if (manifest.exists() && !Regex("\"StateFlags\"\\s+\"4\"").containsMatchIn(manifest.readText())) return null
        return File(env.steamLibrary, TOOL).listFiles { f ->
            f.isDirectory && f.name.startsWith("sniper_platform_") &&
                File(f, "usr-mtree.txt.gz").isFile && File(f, "files").isDirectory
        }?.maxByOrNull { it.name }
    }

    private fun stamp(env: LinuxEnv, platform: File) = "${platform.name} libs=${env.x86Root.name} format=$FORMAT"

    /** True when Steam has a platform that the current rootfs was not built from. */
    fun needsBuild(env: LinuxEnv): Boolean {
        val p = platform(env) ?: return false
        val done = File(root(env), ".complete")
        return !done.exists() || done.readText().trim() != stamp(env, p)
    }

    /** Builds (or rebuilds) the rootfs. Returns false when Steam has no platform yet. */
    @Synchronized
    fun build(env: LinuxEnv, log: (String) -> Unit): Boolean {
        val platform = platform(env) ?: return false.also { log("Mediul jocului: Steam nu a descărcat încă runtime-ul sniper.") }
        val started = System.currentTimeMillis()
        log("Mediul jocului: construiesc din ${platform.name} …")
        val tmp = File(env.files, "sniper-rootfs.new")
        deleteTree(tmp)
        val usr = File(tmp, "usr").apply { mkdirs() }
        var files = 0; var links = 0; var bytes = 0L
        GZIPInputStream(FileInputStream(File(platform, "usr-mtree.txt.gz"))).bufferedReader(Charsets.ISO_8859_1).useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val tok = line.split(' ')
                val path = unescape(tok[0])
                if (path == ".") continue
                val attr = tok.drop(1).mapNotNull { t -> t.indexOf('=').takeIf { it > 0 }?.let { t.substring(0, it) to t.substring(it + 1) } }.toMap()
                val dest = inside(usr, path.removePrefix("./"))
                when (attr["type"]) {
                    "dir" -> dest.mkdirs()
                    "link" -> {
                        dest.parentFile?.mkdirs()
                        Os.symlink(unescape(attr["link"] ?: continue), dest.path)
                        links++
                    }
                    "file" -> {
                        dest.parentFile?.mkdirs()
                        if (attr["size"] == "0") {
                            dest.createNewFile() // The depot does not store empty files.
                        } else {
                            val src = inside(File(platform, "files"), unescape(attr["contents"] ?: path).removePrefix("./"))
                            bytes += copy(src, dest)
                        }
                        chmod(dest, if ((attr["mode"]?.toIntOrNull(8) ?: 0) and EXEC_BITS != 0) MODE_755 else MODE_644)
                        if (++files % 2000 == 0) log("Mediul jocului: $files fișiere …")
                    }
                }
            }
        }
        // The usual merged-/usr layout around it, plus the places programs write to.
        for (d in listOf("bin", "sbin", "lib", "lib32", "lib64", "libx32", "etc")) {
            if (File(usr, d).exists()) Os.symlink("usr/$d", File(tmp, d).path)
        }
        for (d in listOf("tmp", "var/tmp", "dev/shm")) File(tmp, d).apply { mkdirs(); chmod(this, MODE_1777) }
        for (d in listOf("run/pressure-vessel", "home", "root", "var/lib", "var/cache")) File(tmp, d).mkdirs()

        val replaced = overrideCoreLibraries(env.x86Root, usr)
        File(usr, "lib/fexdroid-overrides.txt").writeText(
            "core libraries replaced by copies from ${env.x86Root.name} (Debian 13, glibc 2.41), see GameRootfs.kt\n")
        File(tmp, ".complete").writeText(stamp(env, platform) + "\n")

        val root = root(env)
        deleteTree(root)
        if (!tmp.renameTo(root)) throw IllegalStateException("cannot move ${tmp.name} into place")
        env.writeIdentityFiles() // resolv.conf, hosts
        log("Mediul jocului: gata. $files fișiere, $links legături, ${bytes shr 20} MB, " +
            "$replaced biblioteci înlocuite, ${(System.currentTimeMillis() - started) / 1000} s.")
        return true
    }

    /** Port of scripts/lib/sniper-overrides.py for the merged-/usr tree built above. */
    private fun overrideCoreLibraries(host: File, usr: File): Int {
        var n = 0
        for ((triplet, ldso) in ARCHES) {
            for (name in LIBS + ldso) {
                val src = listOf("usr/lib/$triplet", "lib/$triplet").map { File(host, "$it/$name") }
                    .firstOrNull { it.exists() }?.canonicalFile ?: continue
                val dest = File(usr, "lib/$triplet/$name")
                dest.parentFile?.mkdirs()
                deleteTree(dest) // Usually a symlink to the runtime's own version.
                copy(src, dest)
                chmod(dest, MODE_755)
                n++
            }
        }
        // Program interpreters: /lib64/ld-linux-x86-64.so.2 and /lib/ld-linux.so.2.
        for ((link, target) in listOf(
            "lib64/ld-linux-x86-64.so.2" to "../lib/x86_64-linux-gnu/ld-linux-x86-64.so.2",
            "lib/ld-linux.so.2" to "i386-linux-gnu/ld-linux.so.2",
        )) {
            val f = File(usr, link)
            if (!File(f.parentFile, target).exists()) continue
            f.parentFile?.mkdirs()
            deleteTree(f)
            Os.symlink(target, f.path)
        }
        return n
    }

    /** mtree writes bytes outside printable ASCII (and spaces) as \ooo; names are UTF-8. */
    private fun unescape(s: String): String {
        if ('\\' !in s) return String(s.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 3 < s.length && s.substring(i + 1, i + 4).all { it in '0'..'7' }) {
                out.write(s.substring(i + 1, i + 4).toInt(8)); i += 4
            } else {
                out.write(c.code); i++
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** [rel] below [base], refusing anything that would leave it. */
    private fun inside(base: File, rel: String): File {
        require(rel.isNotEmpty() && !rel.startsWith("/") && rel.split('/').none { it == ".." }) { "bad path in mtree: $rel" }
        return File(base, rel)
    }

    private fun copy(src: File, dest: File): Long =
        FileInputStream(src).channel.use { i -> FileOutputStream(dest).channel.use { o ->
            var pos = 0L
            val size = i.size()
            while (pos < size) pos += i.transferTo(pos, size - pos, o)
            size
        } }

    private fun chmod(f: File, mode: Int) = try { Os.chmod(f.path, mode) } catch (_: ErrnoException) {}

    /** Recursive delete that never follows symlinks (File.deleteRecursively does). */
    private fun deleteTree(f: File) {
        val st = try { Os.lstat(f.path) } catch (_: ErrnoException) { return }
        if (OsConstants.S_ISDIR(st.st_mode)) f.listFiles()?.forEach(::deleteTree)
        f.delete()
    }
}
