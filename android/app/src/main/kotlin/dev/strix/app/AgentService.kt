package dev.strix.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Keeps the process alive and visible while the agent works, so switching apps doesn't kill a long run. */
class AgentService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.agent_channel), NotificationManager.IMPORTANCE_LOW))
        val open = android.app.PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_owl)
            .setContentTitle(getString(R.string.agent_working))
            .setContentText("Tap to return to the session")
            .setContentIntent(open)
            .setOngoing(true)
            .setColor(0xFFFF1744.toInt())
            .build()
        try {
            ServiceCompat.startForeground(this, ID, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "agent"
        private const val ID = 7
        private var active = 0

        /** Called when a run starts or ends. Never throws: a refused foreground start must not crash the app. */
        @Synchronized
        fun setRunning(ctx: Context, running: Boolean) {
            val before = active
            active = (active + if (running) 1 else -1).coerceAtLeast(0)
            try {
                if (before == 0 && active == 1) ctx.startForegroundService(Intent(ctx, AgentService::class.java))
                else if (active == 0 && before > 0) ctx.stopService(Intent(ctx, AgentService::class.java))
            } catch (e: Exception) { /* background start restrictions: the run continues without the notification */ }
        }
    }
}
