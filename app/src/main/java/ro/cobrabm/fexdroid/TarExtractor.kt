package ro.cobrabm.fexdroid

import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Minimal streaming tar extractor (ustar + GNU long names + PAX path/linkpath/size).
 * Written by hand instead of pulling a library so every step of the rootfs
 * installation is visible; handles exactly what `tar --format=gnu/pax` emits.
 */
class TarExtractor(private val dest: File, private val onProgress: (Long) -> Unit = {}) {

    private val destCanonical = dest.canonicalPath
    private val block = ByteArray(512)
    private var bytesDone = 0L
    var files = 0; private set
    var symlinks = 0; private set
    var hardlinks = 0; private set

    fun extract(input: InputStream) {
        var longName: String? = null
        var longLink: String? = null
        var pax: Map<String, String> = emptyMap()
        var zeroBlocks = 0
        while (true) {
            if (!readFully(input, block)) break
            if (block.all { it == 0.toByte() }) {
                if (++zeroBlocks == 2) break else continue
            }
            zeroBlocks = 0
            val type = block[156].toInt().toChar()
            var name = pax["path"] ?: longName ?: header(block)
            val link = pax["linkpath"] ?: longLink ?: cString(block, 157, 100)
            val size = pax["size"]?.toLong() ?: octal(block, 124, 12)
            val mode = octal(block, 100, 8).toInt() and 0x1ff  // rwx bits only, no setuid
            when (type) {
                'L' -> { longName = String(readData(input, size), Charsets.UTF_8).trimEnd('\u0000'); continue }
                'K' -> { longLink = String(readData(input, size), Charsets.UTF_8).trimEnd('\u0000'); continue }
                'x' -> { pax = parsePax(readData(input, size)); continue }
                'g' -> { skip(input, size); continue }
            }
            longName = null; longLink = null; pax = emptyMap()
            name = name.removePrefix("./")
            if (name.isEmpty() || name == ".") { skip(input, size); continue }
            val out = safeFile(name)
            when (type) {
                '5' -> { out.mkdirs(); chmod(out, mode or 0x1c0) }
                '2' -> {
                    out.parentFile?.mkdirs()
                    out.delete()
                    Os.symlink(link, out.path)
                    symlinks++
                }
                '1' -> {
                    out.parentFile?.mkdirs()
                    out.delete()
                    val target = safeFile(link.removePrefix("./"))
                    try { Os.link(target.path, out.path) } catch (e: ErrnoException) { target.copyTo(out, overwrite = true) }
                    hardlinks++
                }
                '0', '\u0000', '7' -> {
                    out.parentFile?.mkdirs()
                    if (out.exists() || isSymlink(out)) out.delete()
                    out.outputStream().use { os ->
                        var left = size
                        val buf = ByteArray(1 shl 16)
                        while (left > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) throw IOException("truncated tar at $name")
                            os.write(buf, 0, n)
                            left -= n
                            bytesDone += n
                        }
                    }
                    skipPadding(input, size)
                    chmod(out, mode or 0x180)
                    files++
                    if (files % 200 == 0) onProgress(bytesDone)
                }
                else -> skip(input, size)  // char/block devices, fifos: not needed
            }
        }
        onProgress(bytesDone)
    }

    private fun safeFile(name: String): File {
        val f = File(dest, name)
        // Canonicalise the parent only: the entry itself may be a dangling symlink.
        val parent = f.parentFile!!.canonicalPath
        if (parent != destCanonical && !parent.startsWith("$destCanonical/"))
            throw IOException("tar entry escapes destination: $name")
        return File(parent, f.name)
    }

    private fun isSymlink(f: File) = try {
        android.system.OsConstants.S_ISLNK(Os.lstat(f.path).st_mode)
    } catch (e: ErrnoException) { false }

    private fun chmod(f: File, mode: Int) = try { Os.chmod(f.path, mode) } catch (_: ErrnoException) {}

    private fun header(b: ByteArray): String {
        val name = cString(b, 0, 100)
        val magic = String(b, 257, 5, Charsets.US_ASCII)
        val prefix = if (magic == "ustar") cString(b, 345, 155) else ""
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    private fun cString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        if (b[off].toInt() and 0x80 != 0) {  // GNU base-256 for large values
            var v = 0L
            for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xff)
            return v
        }
        val s = cString(b, off, len).trim()
        return if (s.isEmpty()) 0 else s.toLong(8)
    }

    private fun parsePax(data: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        while (i < data.size) {
            var sp = i
            while (sp < data.size && data[sp] != ' '.code.toByte()) sp++
            if (sp >= data.size) break
            val len = String(data, i, sp - i).toIntOrNull() ?: break
            val rec = String(data, sp + 1, len - (sp - i) - 2, Charsets.UTF_8)
            val eq = rec.indexOf('=')
            if (eq > 0) out[rec.substring(0, eq)] = rec.substring(eq + 1)
            i += len
        }
        return out
    }

    private fun readData(input: InputStream, size: Long): ByteArray {
        val data = ByteArray(size.toInt())
        if (!readFully(input, data)) throw IOException("truncated tar")
        skipPadding(input, size)
        return data
    }

    private fun skip(input: InputStream, size: Long) {
        var left = size + padding(size)
        while (left > 0) {
            val n = input.skip(left)
            if (n <= 0) { if (input.read() < 0) break else left-- } else left -= n
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = padding(size)
        if (pad > 0) readFully(input, ByteArray(pad.toInt()))
    }

    private fun padding(size: Long) = (512 - size % 512) % 512

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }
}
