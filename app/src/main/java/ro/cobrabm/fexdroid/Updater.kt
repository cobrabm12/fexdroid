package ro.cobrabm.fexdroid

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Updates the app from this repository's GitHub release "apk-latest" (built by
 * .github/workflows/full-apk.yml), so testers do not have to download each build by hand.
 * The release carries fexdroid-legacy.json next to the APK: the commit it was built from,
 * its size and SHA-256. A build is "newer" when its commit differs from ours.
 * The APK goes straight into a PackageInstaller session (no copy on shared storage);
 * Android asks the user to confirm the installation.
 */
object Updater {
    private const val RELEASE = "https://github.com/cobrabm12/fexdroid/releases/download/apk-latest"

    data class Info(val sha: String, val built: String, val size: Long, val sha256: String, val apkUrl: String,
        val version: String = "")

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val info: Info) : State
        data class Downloading(val info: Info, val doneBytes: Long) : State
        /** Android's confirmation dialog is (about to be) on screen. */
        data class Confirming(val info: Info) : State
        data class Failed(val message: String, val info: Info?) : State
    }

    var state by mutableStateOf<State>(State.Idle); private set
    val busy get() = state is State.Checking || state is State.Downloading || state is State.Confirming

    /** Base URL of the release; files/update-url.txt overrides it (tests against a local server). */
    private fun base(ctx: Context): String =
        File(ctx.filesDir, "update-url.txt").takeIf { it.isFile }?.readText()?.trim()?.trimEnd('/') ?: RELEASE

    private fun open(url: String): java.net.URLConnection {
        val conn = URL(url).openConnection()
        if (conn is HttpURLConnection) { // Anything else: a file: URL from update-url.txt.
            conn.instanceFollowRedirects = true // GitHub redirects to its storage host.
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode} de la $url")
        }
        return conn
    }

    /** [quiet]: at app start, a failed check (no internet) is not worth a message. */
    fun check(ctx: Context, quiet: Boolean) {
        if (busy) return
        val app = ctx.applicationContext
        state = State.Checking
        thread(name = "update-check") {
            state = try {
                val base = base(app)
                val conn = open("$base/fexdroid-legacy.json")
                val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val info = Info(j.getString("sha"), j.optString("built"), j.getLong("size"), j.getString("sha256"),
                    "$base/fexdroid-legacy.apk", j.optString("version"))
                if (info.sha == BuildConfig.GIT_SHA) State.UpToDate else State.Available(info)
            } catch (t: Throwable) {
                if (quiet) State.Idle else State.Failed(explain(t), null)
            }
        }
    }

    private fun explain(t: Throwable): String = when (t) {
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException ->
            "Nu există conexiune la internet."
        is java.net.SocketTimeoutException, is javax.net.ssl.SSLException -> "Conexiunea s-a întrerupt. Încearcă din nou."
        else -> t.message ?: t.toString()
    }

    fun install(ctx: Context, info: Info) {
        if (busy) return
        val app = ctx.applicationContext
        state = State.Downloading(info, 0)
        thread(name = "update-download") {
            val installer = app.packageManager.packageInstaller
            var id = -1
            try {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(app.packageName)
                params.setSize(info.size)
                id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val conn = open(info.apkUrl)
                    var done = 0L
                    var reported = 0L
                    session.openWrite("base.apk", 0, info.size).use { out ->
                        conn.inputStream.use { input ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                done += n
                                if (done - reported > (1 shl 20)) { reported = done; state = State.Downloading(info, done) }
                            }
                        }
                        session.fsync(out)
                    }
                    val got = digest.digest().joinToString("") { "%02x".format(it) }
                    if (done != info.size || !got.equals(info.sha256, ignoreCase = true))
                        throw RuntimeException("Fișierul descărcat nu se potrivește cu cel publicat. Încearcă din nou.")
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val result = PendingIntent.getBroadcast(app, id,
                        Intent(app, UpdateReceiver::class.java).setPackage(app.packageName), flags)
                    state = State.Confirming(info)
                    session.commit(result.intentSender)
                }
            } catch (t: Throwable) {
                if (id >= 0) runCatching { installer.abandonSession(id) }
                state = State.Failed(explain(t), info)
            }
        }
    }

    /** From [UpdateReceiver]: what Android said about the session. */
    internal fun onInstallResult(status: Int, message: String?) {
        val info = (state as? State.Confirming)?.info
        state = when (status) {
            PackageInstaller.STATUS_SUCCESS -> State.UpToDate // Normally the app is restarted before this.
            PackageInstaller.STATUS_FAILURE_ABORTED -> if (info != null) State.Available(info) else State.Idle
            // Builds before 2026-09-27 20:00 were signed with a different key on every run.
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, PackageInstaller.STATUS_FAILURE_CONFLICT ->
                State.Failed("Versiunea instalată e semnată cu altă cheie decât cea nouă, așa că Android nu o poate " +
                    "înlocui. O singură dată: dezinstalează aplicația și instaleaz-o din nou de pe GitHub. " +
                    "(${message ?: "cod $status"})", info)
            else -> State.Failed("Instalarea a eșuat: ${message ?: "cod $status"}", info)
        }
    }
}

/** Receives the result of the PackageInstaller session started by [Updater]. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android's own "install this update?" dialog.
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        Updater.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
