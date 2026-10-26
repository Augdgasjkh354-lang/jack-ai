package com.jack.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录音纪要的录音：前台服务里录（锁屏、切到别的 App 也接着录），通知栏显示时长，能直接停。
 * 录成 16 kHz 单声道 AAC（说话够用，一小时约 14 MB），放进附件暂存区；停下后由聊天页交给 Kavi 转文字、出纪要。
 */
class RecordService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> start()
            PAUSE -> pauseOrResume()
            STOP -> finish(keep = true)
            CANCEL -> finish(keep = false)
        }
        return START_NOT_STICKY
    }

    private fun start() {
        if (recorder != null) return
        val stamp = SimpleDateFormat("M月d日 HH.mm", Locale.CHINA).format(Date())
        val dir = File(filesDir, "staging/rec-${System.currentTimeMillis()}").apply { mkdirs() }
        val file = File(dir, "录音 $stamp.m4a")
        try {
            ServiceCompat.startForeground(this, ID, notification(SystemClock.elapsedRealtime(), false),
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            val r = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(32_000)
                setMaxDuration(MAX_MS.toInt())
                setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finish(keep = true) }
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = r
            current = Session(file, SystemClock.elapsedRealtime())
        } catch (e: Exception) {
            recorder?.release(); recorder = null
            dir.deleteRecursively()
            error = "录不了音：" + (e.message ?: e.javaClass.simpleName) + "（麦克风可能被别的 App 占着）"
            stopSelf()
        }
    }

    private fun pauseOrResume() {
        val r = recorder ?: return
        val s = current ?: return
        val now = SystemClock.elapsedRealtime()
        runCatching {
            current = if (s.pausedAt == null) { r.pause(); s.copy(pausedAt = now) }
            else { r.resume(); s.copy(paused = s.paused + (now - s.pausedAt), pausedAt = null) }
        }
        current?.let { c -> getSystemService(NotificationManager::class.java).notify(ID, notification(now - c.elapsed(now), c.pausedAt != null)) }
    }

    private fun finish(keep: Boolean) {
        val s = current
        val r = recorder
        recorder = null
        current = null
        val ok = runCatching { r?.stop() }.isSuccess
        runCatching { r?.release() }
        if (s != null) {
            val secs = s.elapsed(SystemClock.elapsedRealtime()) / 1000
            if (keep && ok && s.file.length() > 0) done = Done(s.file, secs)
            else {
                s.file.parentFile?.deleteRecursively()
                if (keep) error = "这段录音没录上（太短了，或者麦克风出错）"
            }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // 被系统停掉：录到的照样留下
        if (recorder != null) finish(keep = true)
        super.onDestroy()
    }

    private fun notification(base: Long, paused: Boolean) = run {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "录音", NotificationManager.IMPORTANCE_LOW))
        fun service(action: String, code: Int) = PendingIntent.getService(this, code, Intent(this, RecordService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // 「停止并整理」要回到 App：录音交给 Kavi 是在聊天页做的
        val stopAndOpen = PendingIntent.getActivity(this, 7402, Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_RECORD_STOP)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_widget_chat)
            .setContentTitle(if (paused) "录音暂停了" else "正在录音")
            .setContentText("停下后 Kavi 会转成文字、整理成纪要")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(!paused)
            .setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - base))
            .setContentIntent(PendingIntent.getActivity(this, 7403, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, if (paused) "继续" else "暂停", service(PAUSE, 7404))
            .addAction(0, "停止并整理", stopAndOpen)
            .build()
    }

    /** 一次录音：从什么时候开始，暂停了多久（毫秒），现在是不是暂停着。 */
    data class Session(val file: File, val startedAt: Long, val paused: Long = 0, val pausedAt: Long? = null) {
        /** 实际录了多久（不算暂停的）。 */
        fun elapsed(now: Long) = (pausedAt ?: now) - startedAt - paused
    }

    data class Done(val file: File, val seconds: Long)

    companion object {
        private const val CHANNEL = "record"
        private const val ID = 7401
        /** 最长录 3 小时（转文字的上限）。 */
        const val MAX_MS = 3L * 3600 * 1000
        const val START = "com.jack.ai.record.START"
        const val PAUSE = "com.jack.ai.record.PAUSE"
        const val STOP = "com.jack.ai.record.STOP"
        const val CANCEL = "com.jack.ai.record.CANCEL"

        /** 正在录的（null = 没在录）；聊天页看它显示录音条。 */
        var current by mutableStateOf<Session?>(null)
            private set
        /** 刚录完、还没交给 Kavi 的。 */
        var done by mutableStateOf<Done?>(null)
        var error by mutableStateOf<String?>(null)
        /** 正在用的录音机（一次只录一段）。 */
        @Volatile private var recorder: MediaRecorder? = null

        /** 现在的音量 0..1，录音条上画声音跳动用。 */
        fun level(): Float = runCatching { (recorder?.maxAmplitude ?: 0) / 32767f }.getOrDefault(0f).coerceIn(0f, 1f)

        private fun send(ctx: Context, action: String) {
            val i = Intent(ctx, RecordService::class.java).setAction(action)
            runCatching { if (action == START) androidx.core.content.ContextCompat.startForegroundService(ctx, i) else ctx.startService(i) }
                .onFailure { if (action == START) error = "录不了音：${it.message}" }
        }

        fun start(ctx: Context) = send(ctx, START)
        fun pause(ctx: Context) = send(ctx, PAUSE)
        fun stop(ctx: Context) { if (current != null) send(ctx, STOP) }
        fun cancel(ctx: Context) = send(ctx, CANCEL)
    }
}
