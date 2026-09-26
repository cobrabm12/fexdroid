package ro.cobrabm.fexdroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service while a game session exists. Without it Android (and more
 * aggressively Xiaomi/Oppo/Samsung builds) kills the app, and with it FEX, Steam and the
 * game, as soon as the user switches to another app (e.g. Steam Guard for the QR login).
 * It does no work itself: the processes belong to the app process it keeps alive.
 */
class GameService : Service() {
    companion object {
        private const val CHANNEL = "game"
        private const val ID = 1

        fun start(ctx: Context, title: String) {
            val i = Intent(ctx, GameService::class.java).putExtra("title", title)
            runCatching { ctx.startForegroundService(i) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, GameService::class.java)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Joc pornit", NotificationManager.IMPORTANCE_LOW))
        // Launcher intent: brings the existing task back instead of stacking a second activity.
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val open = PendingIntent.getActivity(
            this, 0, launch.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = intent?.getStringExtra("title") ?: "fexdroid"
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("$title rulează")
            .setContentText("Atinge pentru a reveni la joc")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(ID, n)
        return START_NOT_STICKY
    }
}
