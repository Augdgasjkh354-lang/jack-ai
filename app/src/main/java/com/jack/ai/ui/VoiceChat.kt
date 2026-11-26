package com.jack.ai.ui

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jack.ai.ChatViewModel
import com.jack.ai.api.DeepSeekTools
import com.jack.ai.api.Transcriber
import com.jack.ai.chat.VoiceMode
import com.jack.ai.data.Store
import com.jack.ai.data.media.AudioPcm
import com.jack.ai.data.media.Endpointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 连续语音对话：听你说一句 → 转成文字发出去 → Kavi 写完回答就读出来 → 读完接着听，直到你点「结束」。
 * 不用系统的语音识别（很多手机没有）：自己录音、自己判断说完了没有（[Endpointer]），再用「转文字」的服务转成字。
 * 读回答时不录音（不会把自己的声音听进去）；想插话点「打断」。思路参考 AAswordman/Operit（只借思路，代码自己写）。
 */
object VoiceChat {
    enum class Phase { OFF, LISTENING, THINKING, SPEAKING }

    var phase by mutableStateOf(Phase.OFF)
        private set
    /** 声音大小（0～1），听的时候画出来。 */
    var level by mutableFloatStateOf(0f)
        private set
    /** 刚听到的那句。 */
    var heard by mutableStateOf("")
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    val on get() = phase != Phase.OFF

    fun start(ctx: Context, vm: ChatViewModel) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        val engine = Transcriber.engine(vm.state.value.settings)
        if (engine == null) { Toast.makeText(app, "语音对话要先能转文字：" + Transcriber.NO_ENGINE.substringBefore("请用户"), Toast.LENGTH_LONG).show(); return }
        heard = ""
        phase = Phase.LISTENING
        job = scope.launch {
            try { loop(app, vm, engine) } finally {
                if (phase == Phase.SPEAKING) Speaker.stop()
                phase = Phase.OFF; level = 0f; VoiceMode.on = false; VoiceMode.convId = null
            }
        }
    }

    fun stop() { job?.cancel() }

    /** 正在读回答时：不读了，马上听下一句。 */
    fun interrupt() { if (phase == Phase.SPEAKING) Speaker.stop() }

    private suspend fun loop(app: Context, vm: ChatViewModel, engine: Transcriber.Engine) {
        val store = Store(app)
        while (kotlin.coroutines.coroutineContext.isActive) {
            phase = Phase.LISTENING
            VoiceMode.on = true
            VoiceMode.convId = vm.state.value.currentId
            val rec = withContext(Dispatchers.IO) { runCatching { listen(app) } }
            // 麦克风打不开、录音中断：照实说，别说成「没听到你说话」
            rec.exceptionOrNull()?.let { e -> if (e is kotlinx.coroutines.CancellationException) throw e; toast(app, "录音出错：${e.message ?: "麦克风用不了"}，语音对话先停了"); return }
            val wav = rec.getOrNull()
            if (wav == null) { toast(app, "半分钟没听到你说话，语音对话先停了"); return }
            phase = Phase.THINKING
            level = 0f
            val got = withContext(Dispatchers.IO) {
                runCatching { Transcriber.clean(Transcriber.transcribe(engine, wav) { store.addPaidCall(DeepSeekTools.ASR_KEY, it) }) }
                    .also { wav.delete() }
            }
            got.exceptionOrNull()?.let { e -> toast(app, "没转成文字：${e.message ?: "网络问题"}，再说一次") }
            val text = got.getOrNull().orEmpty()
            if (text.isBlank()) continue
            heard = text
            if (!vm.send(text)) { toast(app, "现在发不出去（可能正在回答别的），语音对话先停了"); return }
            val convId = vm.state.value.currentId ?: return
            VoiceMode.convId = convId
            // 等回答开始、写完
            withTimeoutOrNull(15_000) { vm.state.first { convId in it.streamingIds } }
            vm.state.first { convId !in it.streamingIds }
            val reply = vm.state.value.conversations.firstOrNull { it.id == convId }?.messages?.lastOrNull()
                ?.takeIf { it.role == "assistant" && it.content.isNotBlank() } ?: continue
            phase = Phase.SPEAKING
            Speaker.toggle(app, reply.id, reply.content)
            withTimeoutOrNull(8_000) { snapshotFlow { Speaker.speakingId }.first { it == reply.id } }
            snapshotFlow { Speaker.speakingId }.first { it != reply.id }
        }
    }

    /** 录一句话，存成 WAV；半分钟没开口返回 null。 */
    @SuppressLint("MissingPermission")
    private suspend fun listen(app: Context): File? {
        val rate = AudioPcm.RATE
        val ep = Endpointer(rate)
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, ep.frame * 8))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); error("麦克风打不开（可能别的 App 在用）") }
        val all = ByteArrayOutputStream()
        val buf = ShortArray(ep.frame)
        val bytes = ByteArray(ep.frame * 2)
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
                if (st == Endpointer.State.TIMEOUT) return null
                if (st == Endpointer.State.DONE) break
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        val pcm = all.toByteArray()
        val from = (ep.start * 2).coerceIn(0, pcm.size)
        val to = (ep.end * 2).coerceIn(from, pcm.size)
        val f = File(app.cacheDir, "voice-chat.wav")
        f.outputStream().use { it.write(AudioPcm.wavHeader((to - from).toLong())); it.write(pcm, from, to - from) }
        return f
    }

    private fun toast(app: Context, msg: String) = Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
}

/** 语音对话开着时输入框上面的一条：在听 / 在想 / 在说，打断、结束。开着时屏幕不灭。 */
@Composable
internal fun VoiceChatBar() {
    if (!VoiceChat.on) return
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    androidx.compose.runtime.DisposableEffect(view) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    Surface(color = colors.primaryContainer, shape = Radii.m, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
            // 声音大小：一个跟着音量变大的圆点
            val dot = when (VoiceChat.phase) {
                VoiceChat.Phase.LISTENING -> 10f + 14f * VoiceChat.level
                else -> 10f
            }
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(dot.dp).background(if (VoiceChat.phase == VoiceChat.Phase.LISTENING) colors.primary else colors.outline, CircleShape))
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(when (VoiceChat.phase) {
                    VoiceChat.Phase.LISTENING -> "在听…说完停一下就行"
                    VoiceChat.Phase.THINKING -> "在想…"
                    VoiceChat.Phase.SPEAKING -> "在说…想插话点「打断」"
                    VoiceChat.Phase.OFF -> ""
                }, style = MaterialTheme.typography.labelLarge, color = colors.onPrimaryContainer)
                if (VoiceChat.heard.isNotEmpty() && VoiceChat.phase != VoiceChat.Phase.LISTENING)
                    Text("「${VoiceChat.heard}」", style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (VoiceChat.phase == VoiceChat.Phase.SPEAKING) TextButton(onClick = { VoiceChat.interrupt() }) { Text("打断") }
            TextButton(onClick = { VoiceChat.stop() }) { Text("结束") }
        }
    }
}
