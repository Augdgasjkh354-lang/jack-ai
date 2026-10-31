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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jack.ai.api.sharedHttp
import com.jack.ai.data.RemoteClient
import com.jack.ai.data.Store
import com.jack.ai.tools.RemoteChat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 远程连接（设置 → 远程连接）：常驻通知里一直开着，隔几秒去站点取一次别的设备发来的消息，交给 [RemoteChat] 回答。
 * 有活或刚有过活时两秒取一次，闲着五秒一次，连不上就慢慢拉长到半分钟。开着时拿一把部分唤醒锁，屏幕关了也能收到（耗电，设置里写明了）。
 * 一次答一条，按先来后到；网页上点「停止」就停掉那条。
 */
class RemoteService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Mutex()
    private val running = ConcurrentHashMap<Long, Job>()
    private var wake: PowerManager.WakeLock? = null
    @Volatile private var lastWork = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stoppedByUser = true; stopSelf(); return START_NOT_STICKY }
        try {
            ServiceCompat.startForeground(this, ID, notice("正在连站点…"), type())
        } catch (e: Exception) {
            _status.value = "开不了常驻通知：${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop == null) start()
        return START_STICKY
    }

    private var loop: Job? = null

    private fun start() {
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kavi:remote").apply { setReferenceCounted(false); acquire() }
        loop = scope.launch {
            var fails = 0
            while (isActive) {
                val s = Store(this@RemoteService).loadSettings()
                if (!s.remoteOn || s.remoteUrl.isBlank() || s.remoteKey.isBlank()) { stopSelf(); return@launch }
                val client = RemoteClient(s.remoteUrl, s.remoteKey, sharedHttp)
                try {
                    val p = client.poll()
                    fails = 0
                    p.answers.forEach { (id, ok) -> RemoteChat.answered(id, ok) }
                    p.stops.forEach { id -> running.remove(id)?.cancel() }
                    p.messages.forEach { m ->
                        lastWork = System.currentTimeMillis()
                        running[m.id] = scope.launch {
                            var started = false
                            try {
                                queue.withLock { started = true; RemoteChat.reply(this@RemoteService, client, m) }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                // 还在排队就被停了：也写一句，网页上才不会一直等
                                if (!started) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                    runCatching { client.reply(m.id, "（已停止）", emptyList(), emptyList(), "done") }
                                }
                                throw e
                            } catch (e: Exception) {
                                runCatching { client.reply(m.id, "出错了：" + com.jack.ai.tools.ToolSession.errorText(e), emptyList(), emptyList(), "error") }
                            } finally {
                                running.remove(m.id)
                                lastWork = System.currentTimeMillis()
                                update()
                            }
                        }
                    }
                    update()
                } catch (e: Exception) {
                    fails++
                    _status.value = "连不上站点：" + (e.message ?: e.javaClass.simpleName).take(80)
                    notify(_status.value)
                }
                val busy = running.isNotEmpty() || System.currentTimeMillis() - lastWork < 2 * 60_000
                delay(if (fails > 0) minOf(30_000L, 2_000L * (1 shl minOf(fails, 4))) else if (busy) 2_000L else 5_000L)
            }
        }
    }

    private fun update() {
        val text = if (running.isEmpty()) "在线，等别的设备发消息" else "正在回答远程消息（${running.size}）"
        _status.value = text
        notify(text)
    }

    private fun notify(text: String) = runCatching {
        getSystemService(NotificationManager::class.java).notify(ID, notice(text))
    }

    private fun notice(text: String) = run {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "远程连接", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, RemoteService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_widget_chat)
            .setContentTitle("Kavi 远程连接")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "断开", stop)
            .build()
    }

    override fun onDestroy() {
        loop?.cancel()
        scope.cancel()
        runCatching { wake?.release() }
        _status.value = "没开"
        // 通知栏点了「断开」：设置里也关掉，免得下次开 App 又连上
        runCatching {
            val store = Store(this)
            val s = store.loadSettings()
            if (s.remoteOn && stoppedByUser) store.saveSettings(s.copy(remoteOn = false))
        }
        stoppedByUser = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "remote"
        private const val ID = 7401
        private const val ACTION_STOP = "com.jack.ai.REMOTE_STOP"
        @Volatile private var stoppedByUser = false
        private val _status = MutableStateFlow("没开")
        /** 给设置页看的状态。 */
        val status: StateFlow<String> = _status

        /** 安卓 14 以上用「远程消息」类型（专门给跨设备接着聊天用的，没有每天的时长限制）。 */
        private fun type(): Int = when {
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }

        /** 按设置开或关（开 App、改设置时调）。 */
        fun sync(ctx: Context) {
            val s = Store(ctx).loadSettings()
            val i = Intent(ctx, RemoteService::class.java)
            if (s.remoteOn && s.remoteUrl.isNotBlank() && s.remoteKey.isNotBlank()) runCatching { ContextCompat.startForegroundService(ctx, i) }
            else ctx.stopService(i)
        }

    }
}
