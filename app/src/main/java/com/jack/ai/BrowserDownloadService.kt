package com.jack.ai

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.jack.ai.data.BrowserDownloads
import com.jack.ai.api.BrowserQueue

/** 合并所有下载的进度，前台服务让锁屏后的网络传输继续。 */
class BrowserDownloadService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (BrowserDownloads.active == 0) { stopSelf(); return }
            val list = BrowserDownloads.tasks.filter { it.state == BrowserQueue.State.RUNNING }
            val known = list.isNotEmpty() && list.all { it.total > 0 }
            val total = list.sumOf { it.total.coerceAtLeast(0) }
            val percent = if (known) (list.sumOf { it.got } * 100 / total).toInt().coerceIn(0, 100) else 0
            val open = PendingIntent.getActivity(this@BrowserDownloadService, 0,
                Intent(this@BrowserDownloadService, MainActivity::class.java).setAction(MainActivity.ACTION_DOWNLOADS)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(this@BrowserDownloadService, CHANNEL).setSmallIcon(R.drawable.ic_widget_chat)
                .setContentTitle("正在下载 ${list.size} 个文件" + if (known) "，$percent%" else "")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setProgress(100, percent, !known).build()
            ServiceCompat.startForeground(this@BrowserDownloadService, ID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "网页下载", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(tick); tick.run(); return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        BrowserDownloads.tasks.filter { it.state == BrowserQueue.State.RUNNING }.forEach { if (it.pausable) BrowserDownloads.pause(it) else BrowserDownloads.cancel(it) }
        stopSelf()
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "browser_downloads"
        private const val ID = 7311
        fun start(ctx: Context) { runCatching { androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, BrowserDownloadService::class.java)) } }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, BrowserDownloadService::class.java)) }
    }
}
