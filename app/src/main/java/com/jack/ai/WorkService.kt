package com.jack.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.jack.ai.data.Routines

/**
 * 回答期间（Chat 和 Work 都是）开着的前台服务：App 切到后台也不会被系统停掉，通知栏显示进度。
 * 服务本身不干活，活还是 ChatViewModel 里的协程在干；它只是让进程活着。
 */
class WorkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra("title").orEmpty()
        val text = intent?.getStringExtra("text").orEmpty()
        val convId = intent?.getStringExtra("convId")
        val n = build(this, title, text, convId, intent?.getBooleanExtra("work", false) == true)
        try {
            ServiceCompat.startForeground(
                this, ID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        } catch (e: Exception) {
            // 某些情况下（比如 App 不在前台时）系统不让开前台服务：不开也能照常干活，只是可能被停掉
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "work"
        private const val ID = 7301
        /** 上次更新通知的时间，免得刷太勤。 */
        @Volatile private var lastUpdate = 0L

        private fun channel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "回答和任务进度", NotificationManager.IMPORTANCE_LOW))
            }
        }

        private fun openIntent(ctx: Context, convId: String?) = PendingIntent.getActivity(
            ctx, (convId ?: "work").hashCode(),
            Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_CHAT)
                .putExtra("convId", convId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun build(ctx: Context, title: String, text: String, convId: String?, work: Boolean) = run {
            channel(ctx)
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_widget_chat)
                .setContentTitle((if (work) "正在工作：" else "正在回答：") + title)
                .setContentText(text.ifBlank { "进行中…" })
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .setContentIntent(openIntent(ctx, convId))
                .build()
        }

        fun start(ctx: Context, convId: String, title: String, work: Boolean) {
            val i = Intent(ctx, WorkService::class.java).putExtra("convId", convId).putExtra("title", title).putExtra("work", work)
            runCatching { androidx.core.content.ContextCompat.startForegroundService(ctx, i) }
        }

        /** 更新通知上的进度文字（最多每秒一次）。 */
        fun progress(ctx: Context, convId: String, title: String, text: String, work: Boolean) {
            val now = System.currentTimeMillis()
            if (now - lastUpdate < 1000) return
            lastUpdate = now
            if (!Routines.canNotify(ctx)) return
            try {
                NotificationManagerCompat.from(ctx).notify(ID, build(ctx, title, text, convId, work))
            } catch (_: SecurityException) {
            }
        }

        /** 干完了：关掉前台服务；App 不在眼前时发一条完成通知。 */
        fun finish(ctx: Context, convId: String, title: String, result: String, work: Boolean) {
            ctx.stopService(Intent(ctx, WorkService::class.java))
            // 前台服务没开成时，进度是普通的常驻通知，停服务撤不掉它，要自己撤（不然一直挂着「正在回答」划不掉）
            runCatching { NotificationManagerCompat.from(ctx).cancel(ID) }
            if (!MainActivity.visible) Routines.notify(ctx, "work-$convId", (if (work) "完成了：" else "回答好了：") + title, result, openChat = convId)
        }
    }
}
