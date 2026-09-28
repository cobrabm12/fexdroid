package ro.cobrabm.fexdroid

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * Downloads and installs the Steam x86 rootfs (scripts/build-rootfs-steam.sh: Debian
 * amd64 + i386 packages only, no Valve files) from this repository's GitHub release,
 * built by .github/workflows/steam-rootfs.yml. Replaces the adb step of
 * scripts/deploy-phone.sh for phones without a PC at hand.
 * The archive is extracted while it downloads (no second copy on flash).
 */
object SteamRootfs {
    const val URL = "https://github.com/cobrabm12/fexdroid/releases/download/steam-rootfs/rootfs-x86_64-steam.tar.gz"

    sealed interface State {
        data object Idle : State
        data class Working(val doneBytes: Long, val totalBytes: Long) : State
        data class Failed(val message: String) : State
        data object Done : State
    }

    var state by mutableStateOf<State>(State.Idle); private set
    val busy get() = state is State.Working

    /** What went wrong, in words a player can act on. */
    private fun explain(t: Throwable, env: LinuxEnv): String = when (t) {
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException ->
            str(R.string.error_no_internet_long)
        is java.net.SocketTimeoutException, is javax.net.ssl.SSLException ->
            str(R.string.error_connection_lost_download)
        is java.io.IOException ->
            if (env.files.usableSpace < (700L shl 20)) str(R.string.error_no_space)
            else str(R.string.error_download_failed, (t.message ?: t).toString())
        else -> t.message ?: t.toString()
    }

    fun install(ctx: Context) {
        if (busy) return
        val env = LinuxEnv(ctx.applicationContext)
        state = State.Working(0, -1)
        thread(name = "steam-rootfs") {
            val tmp = File(env.files, "x86_64-steam.part")
            try {
                tmp.deleteRecursively()
                tmp.mkdirs()
                var conn = URL(URL).openConnection() as HttpURLConnection
                // GitHub answers with a redirect to its storage host.
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode} de la $URL")
                val total = conn.contentLengthLong
                var done = 0L
                val counting = object : java.io.FilterInputStream(conn.inputStream.buffered(1 shl 16)) {
                    private var lastReport = 0L
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) {
                            done += n
                            if (done - lastReport > (1 shl 20)) { lastReport = done; state = State.Working(done, total) }
                        }
                        return n
                    }
                }
                GZIPInputStream(counting, 1 shl 16).use { TarExtractor(tmp).extract(it) }
                conn.disconnect()
                File(tmp, ".complete").writeText("$URL\n")
                env.x86Steam.deleteRecursively()
                if (!tmp.renameTo(env.x86Steam)) throw RuntimeException("cannot rename ${tmp.path}")
                state = State.Done
            } catch (t: Throwable) {
                tmp.deleteRecursively()
                state = State.Failed(explain(t, env))
            }
        }
    }
}
