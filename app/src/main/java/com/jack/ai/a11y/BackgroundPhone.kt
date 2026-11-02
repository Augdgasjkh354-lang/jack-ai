package com.jack.ai.a11y

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.Display
import androidx.core.app.NotificationCompat
import com.jack.ai.ui.BackgroundPhoneActivity
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** Mika 虚拟屏幕在 Jack AI 的任务宿主。只有 phone 工具第一次使用时才建屏。 */
class BackgroundPhone private constructor(
    private val app: Context,
    val shell: IMikaShell,
    val display: AgentDisplay,
    val title: String,
) {
    @Volatile var stopped = false
        private set
    private val _paused = MutableStateFlow(false)
    val paused = _paused.asStateFlow()
    private val operations = Mutex()
    @Volatile var revision = 0
        private set
    private var handedTask = -1
    private var closed = false
    val displayId get() = display.displayId

    suspend fun ready() {
        while (_paused.value && !stopped && shell.asBinder().isBinderAlive && PhoneServices.of(app).hub.shell.value === shell) delay(200)
        if (stopped || closed) throw IOException("后台手机操作已停止，不要换到主屏继续操作。")
        if (PhoneServices.of(app).hub.shell.value !== shell || !shell.asBinder().isBinderAlive) {
            throw IOException("后台手机助手已断开，请到 设置 → 操作其他 App 重新连接；没有切换到主屏。")
        }
    }

    /** 接管与工具动作共用一把锁，避免读到的控件在点击前被挪到主屏。 */
    suspend fun <T> control(action: suspend () -> T): T {
        while (true) {
            ready()
            operations.withLock {
                if (!_paused.value) {
                    ready()
                    return action()
                }
            }
        }
    }

    fun stop() {
        stopped = true
        KaviAccessibility.stops.incrementAndGet()
    }

    /** 用户主动接管：先暂停模型，再把当前任务移到主屏；不会另开一份 App。 */
    suspend fun handOver(): Boolean = withContext(Dispatchers.IO) { operations.withLock {
        if (stopped || _paused.value) return@withLock false
        _paused.value = true
        try {
            val task = shell.topTaskId(displayId)
            val moved = task >= 0 && shell.moveTask(task, Display.DEFAULT_DISPLAY)
            if (moved) { handedTask = task; revision++ } else _paused.value = false
            moved
        } catch (e: Exception) { _paused.value = false; throw e }
    } }

    suspend fun resume(): Boolean = withContext(Dispatchers.IO) { operations.withLock {
        if (stopped || !_paused.value) return@withLock false
        val moved = handedTask >= 0 && shell.moveTask(handedTask, displayId)
        if (moved) { handedTask = -1; revision++; _paused.value = false }
        moved
    } }

    suspend fun close() = operations.withLock {
        if (closed) return@withLock
        closed = true
        // 用户接管中的任务已经在主屏，不随虚拟屏关闭；其余沿用 Mika 的任务收尾规则。
        runCatching { shell.finishDisplay(displayId, emptyArray(), emptyArray()) }
        display.release()
        runCatching { shell.restoreClip() }
        currentState.compareAndSet(this, null)
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTICE)
    }

    private fun notifyRunning() {
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "后台操作 App", NotificationManager.IMPORTANCE_LOW))
        val view = PendingIntent.getActivity(app, NOTICE, Intent(app, BackgroundPhoneActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getBroadcast(app, NOTICE, Intent(app, PhonePairingReceiver::class.java).setAction(PhonePairingReceiver.STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val note = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Kavi 正在后台操作 App")
            .setContentText("$title · 点此查看或接管").setContentIntent(view).setOngoing(true)
            .addAction(0, "停止", stop).build()
        runCatching { manager.notify(NOTICE, note) }
    }

    companion object {
        private const val CHANNEL = "phone-background"
        private const val NOTICE = 6101
        private val currentState = MutableStateFlow<BackgroundPhone?>(null)
        val current = currentState.asStateFlow()

        suspend fun create(context: Context, title: String): BackgroundPhone = withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < 31) throw IOException("后台操作需要安卓 12 以上；可在设置里关闭后台模式使用原来的主屏操作。")
            val app = context.applicationContext
            val services = PhoneServices.of(app)
            services.shellManager.start()
            services.shellManager.need()
            val helper = withTimeoutOrNull(15_000) { services.hub.shell.first { it != null } }
                ?: throw IOException("后台手机助手尚未连接。请到 设置 → 操作其他 App 完成无线调试配对或点重新连接；没有占用主屏。")
            val metrics = app.resources.displayMetrics
            val display = AgentDisplay(helper, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
            BackgroundPhone(app, helper, display, title).also {
                currentState.value = it
                it.notifyRunning()
            }
        }
    }
}
