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
        try {
            ServiceCompat.startForeground(
                this, ID, summary(this),
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

        /** 一个正在进行的回答：对话标题、最新进度、是不是 Work、什么时候开始的。 */
        private data class Run(val title: String, val text: String, val work: Boolean, val at: Long)
        /** 正在进行的回答（几个对话可以同时答），一条通知里一起显示；全都结束才关前台服务。 */
        private val runs = java.util.concurrent.ConcurrentHashMap<String, Run>()

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

        /** 通知的标题和文字：一个回答就照旧；几个同时进行就写「N 个在回答」，下面一行一个。点开最新开始的那个。 */
        internal fun summaryText(list: List<Pair<String, Triple<String, String, Boolean>>>): Pair<String, String> {
            if (list.size == 1) {
                val (title, text, work) = list.single().second
                return ((if (work) "正在工作：" else "正在回答：") + title) to text.ifBlank { "进行中…" }
            }
            return "${list.size} 个在同时进行" to list.joinToString("\n") { (_, r) -> "${r.first}：${r.second.ifBlank { "进行中…" }}" }
        }

        private fun summary(ctx: Context) = run {
            channel(ctx)
            val list = runs.entries.sortedBy { it.value.at }.map { it.key to Triple(it.value.title, it.value.text, it.value.work) }
                .ifEmpty { listOf("" to Triple("", "", false)) }
            val (title, text) = summaryText(list)
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_widget_chat)
                .setContentTitle(title)
                .setContentText(text.lineSequence().first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .setContentIntent(openIntent(ctx, list.last().first.ifEmpty { null }))
                .build()
        }

        private fun post(ctx: Context) {
            if (!Routines.canNotify(ctx)) return
            try {
                NotificationManagerCompat.from(ctx).notify(ID, summary(ctx))
            } catch (_: SecurityException) {
            }
        }

        fun start(ctx: Context, convId: String, title: String, work: Boolean) {
            runs[convId] = Run(title, "", work, System.currentTimeMillis())
            // 已经在前台就只是更新通知；没开过就开（重复开会更新同一条通知）
            runCatching { androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, WorkService::class.java)) }
        }

        /** 更新通知上的进度文字（最多每秒一次）。 */
        fun progress(ctx: Context, convId: String, title: String, text: String, work: Boolean) {
            val old = runs[convId] ?: return
            runs[convId] = old.copy(title = title, text = text, work = work)
            val now = System.currentTimeMillis()
            if (now - lastUpdate < 1000) return
            lastUpdate = now
            post(ctx)
        }

        /** 干完了：别的还在进行就只更新通知，全都完了才关掉前台服务；App 不在眼前时发一条完成通知。 */
        fun finish(ctx: Context, convId: String, title: String, result: String, work: Boolean) {
            runs.remove(convId)
            if (runs.isEmpty()) {
                ctx.stopService(Intent(ctx, WorkService::class.java))
                // 前台服务没开成时，进度是普通的常驻通知，停服务撤不掉它，要自己撤（不然一直挂着「正在回答」划不掉）
                runCatching { NotificationManagerCompat.from(ctx).cancel(ID) }
            } else post(ctx)
            if (!MainActivity.visible) Routines.notify(ctx, "work-$convId", (if (work) "完成了：" else "回答好了：") + title, result, openChat = convId)
        }
    }
}
