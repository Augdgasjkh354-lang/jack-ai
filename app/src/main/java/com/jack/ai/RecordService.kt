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
 * 分段存（每段约 16 分钟）：录音中途 App 被系统杀掉，录完的段都是好文件，只丢最后一段；下次打开时 [recover] 拼起来救回。
 * 停下时把几段拼成一个文件。
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

    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    /** 录满 3 小时就停（分段录时 MediaRecorder 自带的时长上限不可靠，自己数）。 */
    private val limit = object : Runnable {
        override fun run() {
            val c = current ?: return
            if (c.elapsed(SystemClock.elapsedRealtime()) >= MAX_MS) finish(keep = true) else ticker.postDelayed(this, 10_000)
        }
    }

    private fun start() {
        if (recorder != null) return
        val stamp = SimpleDateFormat("M月d日 HH.mm", Locale.CHINA).format(Date())
        val dir = File(filesDir, "staging/rec-${System.currentTimeMillis()}").apply { mkdirs() }
        val file = File(dir, "录音 $stamp.m4a")
        // 记下最后要叫什么：被杀后救回时用
        runCatching { File(dir, NAME_FILE).writeText(file.name) }
        var part = 1
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
                setMaxFileSize(SEGMENT_BYTES)
                setOutputFile(partFile(dir, part).absolutePath)
                setOnInfoListener { mr, what, _ ->
                    when (what) {
                        // 这段快满了：接着录进下一段，这一段收尾成完整的文件
                        MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING ->
                            runCatching { part++; mr.setNextOutputFile(partFile(dir, part)) }.onFailure { finish(keep = true) }
                        // 下一段没接上就满了：录到的先留下
                        MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> finish(keep = true)
                    }
                }
                prepare()
                start()
            }
            recorder = r
            current = Session(file, SystemClock.elapsedRealtime())
            ticker.postDelayed(limit, 10_000)
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
        ticker.removeCallbacks(limit)
        val s = current
        val r = recorder
        recorder = null
        current = null
        runCatching { r?.stop() }
        runCatching { r?.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (s == null) { stopSelf(); return }
        val dir = s.file.parentFile ?: return
        if (!keep) { dir.deleteRecursively(); stopSelf(); return }
        val secs = s.elapsed(SystemClock.elapsedRealtime()) / 1000
        // 几段拼成一个文件：3 小时的录音要拼几秒，不放在主线程
        Thread {
            val result = runCatching { assemble(dir, s.file) }.getOrNull()
            if (result != null) done = Done(result, secs)
            else { dir.deleteRecursively(); error = "这段录音没录上（太短了，或者麦克风出错）" }
            stopSelf()
        }.start()
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
        /** 每段的大小上限：32 kbps 下约 16 分钟。被杀时最多丢这么多。 */
        private const val SEGMENT_BYTES = 4L * 1024 * 1024
        private const val NAME_FILE = "name.txt"
        /** 拼好、交出去了：救回时跳过。 */
        private const val DONE_MARK = ".done"
        private fun partFile(dir: File, n: Int) = File(dir, "part-%03d.m4a".format(n))

        /** 能打开、有声音的段（被杀时正在录的那段没收尾，打不开，丢掉）。 */
        private fun playable(f: File): Boolean = f.length() > 0 && runCatching {
            val ex = android.media.MediaExtractor()
            try {
                ex.setDataSource(f.absolutePath)
                (0 until ex.trackCount).any { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            } finally { ex.release() }
        }.getOrDefault(false)

        /** 把目录里能用的段拼成 target，删掉各段，打上「已交出」标记。一段都没有返回 null。 */
        private fun assemble(dir: File, target: File): File? {
            val parts = dir.listFiles { f -> f.name.startsWith("part-") && f.name.endsWith(".m4a") }.orEmpty().sortedBy { it.name }.filter(::playable)
            if (parts.isEmpty()) return null
            if (parts.size == 1) { if (!parts[0].renameTo(target)) parts[0].copyTo(target, overwrite = true) }
            else com.jack.ai.data.MediaMux.concatAudio(parts, target)
            parts.forEach { if (it != target) it.delete() }
            File(dir, DONE_MARK).createNewFile()
            return target
        }

        /** 时长（秒）。 */
        private fun seconds(f: File): Long = runCatching {
            val mr = android.media.MediaMetadataRetriever()
            try { mr.setDataSource(f.absolutePath); (mr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000 } finally { mr.release() }
        }.getOrDefault(0L)

        /**
         * 上次录音中途被系统杀掉（或者录完了还没来得及交给 Kavi）：把录到的救回来。要在后台线程调。
         * 正在录的时候不动。救不回来的空目录顺手清掉。
         */
        fun recover(ctx: Context): List<Done> {
            if (current != null || recorder != null) return emptyList()
            val dirs = File(ctx.filesDir, "staging").listFiles { f -> f.isDirectory && f.name.startsWith("rec-") }.orEmpty().sortedBy { it.name }
            return dirs.mapNotNull { dir ->
                if (File(dir, DONE_MARK).exists()) return@mapNotNull null
                // 没有 name.txt 的是老版本录的：那时录完就交出去了，不再重复救
                val name = runCatching { File(dir, NAME_FILE).readText().trim() }.getOrNull() ?: return@mapNotNull null
                if (!name.endsWith(".m4a")) return@mapNotNull null
                val target = File(dir, name)
                // 拼好了、只是没交出去
                val file = if (playable(target)) target.also { File(dir, DONE_MARK).createNewFile() }
                    else runCatching { assemble(dir, target) }.getOrNull()
                if (file == null) { dir.deleteRecursively(); null } else Done(file, seconds(file))
            }
        }

        private const val CHANNEL = "record"
        private const val ID = 7402
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
