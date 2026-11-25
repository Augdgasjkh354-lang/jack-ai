package com.jack.ai.ui

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jack.ai.ChatViewModel
import com.jack.ai.receiveText
import com.jack.ai.api.DeepSeekTools
import com.jack.ai.api.Transcriber
import com.jack.ai.data.Store
import com.jack.ai.data.media.AudioPcm
import com.jack.ai.data.media.Endpointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 话筒点一下的语音输入：Kavi 自己录音、自己转文字，转好的字放进输入框（不自动发，改完再发）。
 * 不用手机系统的语音识别：很多手机上那个服务是 Google 的，国内连不上（卸了 Google 输入法也没用，它在 Google App 里）。
 * 说完停顿一会儿自动结束，也能点「完成」；转文字用 [Transcriber]（DeepSeek Key 就行，硅基流动更准）。
 */
object VoiceInput {
    enum class Phase { OFF, LISTENING, TRANSCRIBING }

    var phase by mutableStateOf(Phase.OFF)
        private set
    /** 声音大小（0～1），录音条上画出来。 */
    var level by mutableFloatStateOf(0f)
        private set
    /** 开始录的时间（毫秒），录音条上走秒。 */
    var startedAt by mutableLongStateOf(0L)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    @Volatile private var finishNow = false

    val on get() = phase != Phase.OFF

    /** 能不能用 Kavi 自己转文字（有 DeepSeek Key 或者硅基流动这类的 Key）。 */
    fun available(vm: ChatViewModel) = Transcriber.engine(vm.state.value.settings) != null

    fun start(ctx: Context, vm: ChatViewModel) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        val engine = Transcriber.engine(vm.state.value.settings) ?: return
        finishNow = false
        level = 0f
        startedAt = System.currentTimeMillis()
        phase = Phase.LISTENING
        job = scope.launch {
            try {
                val rec = withContext(Dispatchers.IO) { runCatching { record(app) } }
                rec.exceptionOrNull()?.let { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    toast(app, "录音出错：${e.message ?: "麦克风用不了"}")
                    return@launch
                }
                val wav = rec.getOrNull() ?: run { toast(app, "没听到说话"); return@launch }
                phase = Phase.TRANSCRIBING
                level = 0f
                val store = Store(app)
                val got = withContext(Dispatchers.IO) {
                    runCatching { Transcriber.clean(Transcriber.transcribe(engine, wav) { store.addPaidCall(DeepSeekTools.ASR_KEY, it) }) }
                        .also { wav.delete() }
                }
                got.exceptionOrNull()?.let { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    toast(app, "没转成文字：${e.message ?: "网络问题"}")
                }
                got.getOrNull()?.takeIf { it.isNotBlank() }?.let { vm.receiveText(it) }
            } finally {
                phase = Phase.OFF; level = 0f
            }
        }
    }

    /** 截图测试用：不录音，直接摆出录音条的样子。 */
    internal fun pretend(p: Phase, lv: Float = 0.6f) { phase = p; level = lv; startedAt = System.currentTimeMillis() - 7_000 }

    /** 说完了：用已经录到的转文字。 */
    fun finish() { finishNow = true }

    /** 不要了：录的扔掉。 */
    fun cancel() { job?.cancel() }

    /** 录一段，存成 WAV。停顿约 2 秒自动结束，最长 110 秒（DeepSeek 一段的上限）；15 秒没开口返回 null。 */
    @SuppressLint("MissingPermission")
    private suspend fun record(app: Context): File? {
        val rate = AudioPcm.RATE
        val ep = Endpointer(rate, silenceMs = 2000, waitMs = 15_000, maxMs = DeepSeekTools.ASR_MAX_SEC * 1000)
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, ep.frame * 8))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); error("麦克风打不开（可能别的 App 在用）") }
        val all = ByteArrayOutputStream()
        val buf = ShortArray(ep.frame)
        val bytes = ByteArray(ep.frame * 2)
        var spoke = false
        try {
            rec.startRecording()
            while (true) {
                kotlin.coroutines.coroutineContext.ensureActive()
                var got = 0
                while (got < buf.size) { val n = rec.read(buf, got, buf.size - got); if (n <= 0) error("录音中断了"); got += n }
                for (i in buf.indices) { bytes[i * 2] = buf[i].toByte(); bytes[i * 2 + 1] = (buf[i].toInt() shr 8).toByte() }
                all.write(bytes)
                val st = ep.push(buf)
                level = ep.level
                if (st == Endpointer.State.SPEAKING) spoke = true
                if (st == Endpointer.State.TIMEOUT) return null
                if (st == Endpointer.State.DONE) break
                // 点了「完成」：说过话就用到现在为止的；没开口就当没说
                if (finishNow) { if (!spoke) return null; break }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        val pcm = all.toByteArray()
        val from = (ep.start * 2).coerceIn(0, pcm.size)
        val to = (if (ep.state == Endpointer.State.DONE) ep.end * 2 else pcm.size).coerceIn(from, pcm.size)
        val f = File(app.cacheDir, "voice-input.wav")
        f.outputStream().use { it.write(AudioPcm.wavHeader((to - from).toLong())); it.write(pcm, from, to - from) }
        return f
    }

    private fun toast(app: Context, msg: String) = Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
}
