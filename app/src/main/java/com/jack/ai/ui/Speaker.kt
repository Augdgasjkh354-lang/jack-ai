package com.jack.ai.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jack.ai.api.DeepSeekTools
import com.jack.ai.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** 朗读回答：默认用手机自带的语音引擎；设置里开了「DeepSeek 语音」就用 DeepSeek TTS（边合成边播）。 */
object Speaker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** 正在跑的 DeepSeek 朗读。 */
    private var dsJob: Job? = null
    private var track: android.media.AudioTrack? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: (() -> Unit)? = null

    /** 正在朗读哪条消息。 */
    var speakingId by mutableStateOf<String?>(null)
        private set

    fun toggle(ctx: Context, id: String, markdown: String) {
        if (speakingId == id) {
            stop()
            return
        }
        val text = plain(markdown)
        // 没有能读的字时别标成“正在朗读”，不然按钮一直停在朗读状态
        if (text.isBlank()) return
        if (PageReader.active) PageReader.stop()
        stopDs()
        val app = ctx.applicationContext
        speakingId = id
        dsJob = scope.launch {
            val st = withContext(Dispatchers.IO) { runCatching { Store(app).loadSettings() }.getOrNull() }
            if (st != null && st.dsVoice && st.apiKey.isNotBlank()) {
                tts?.stop()
                val err = withContext(Dispatchers.IO) { runCatching { speakDs(st.apiKey, text, st.dsVoiceId, Store(app)) }.exceptionOrNull() }
                if (err != null && err !is kotlinx.coroutines.CancellationException && isActive)
                    Toast.makeText(app, (err.message ?: "DeepSeek 朗读失败") + "，改用手机自带的朗读", Toast.LENGTH_LONG).show()
                else {
                    if (speakingId == id) speakingId = null
                    return@launch
                }
            }
            speakPhone(app, id, text)
        }
    }

    /** DeepSeek TTS：按计费字符切段，一段段拉 PCM 流写进 AudioTrack。 */
    private suspend fun speakDs(key: String, text: String, voice: String, store: Store) = kotlinx.coroutines.coroutineScope {
        val rate = DeepSeekTools.TTS_RATE
        val min = android.media.AudioTrack.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
        val t = android.media.AudioTrack.Builder()
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(android.media.AudioFormat.Builder()
                .setSampleRate(rate).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min * 4, rate))
            .setTransferMode(android.media.AudioTrack.MODE_STREAM).build()
        track = t
        try {
            t.play()
            var written = 0L
            for (chunk in DeepSeekTools.ttsChunks(text)) {
                if (!isActive) break
                DeepSeekTools.ttsStream(key, chunk, voice).use { input ->
                    store.addPaidCall(DeepSeekTools.TTS_KEY, DeepSeekTools.ttsChars(chunk) * DeepSeekTools.TTS_PRICE_PER_CHAR)
                    val buf = ByteArray(8192)
                    var odd = -1 // 上一块剩下的半个采样（PCM 16 位要按 2 字节对齐写）
                    while (isActive && t.playState == android.media.AudioTrack.PLAYSTATE_PLAYING) {
                        var n = input.read(buf, 1, buf.size - 1)
                        if (n < 0) break
                        var start = 1
                        if (odd >= 0) { buf[0] = odd.toByte(); start = 0; n += 1; odd = -1 }
                        if (n % 2 == 1) { odd = buf[start + n - 1].toInt() and 0xff; n -= 1 }
                        if (n > 0) { t.write(buf, start, n); written += n }
                    }
                }
            }
            // 等缓冲里的声音放完再结束
            if (isActive) {
                val frames = (written / 2).toInt()
                while (isActive && t.playbackHeadPosition < frames && t.playState == android.media.AudioTrack.PLAYSTATE_PLAYING) kotlinx.coroutines.delay(100)
            }
        } finally {
            runCatching { t.stop() }
            t.release()
            if (track === t) track = null
        }
    }

    private fun stopDs() {
        dsJob?.cancel()
        dsJob = null
        // stop() 会叫醒正卡在 write() 里的线程，pause() 不会
        track?.let { runCatching { it.stop(); it.flush() } }
    }

    private fun speakPhone(app: Context, id: String, text: String) {
        val run = {
            val t = tts!!
            t.language = Locale.CHINA
            t.stop()
            val chunks = text.chunked(3500)
            chunks.forEachIndexed { i, c ->
                t.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (i == chunks.lastIndex) "$id#last" else "$id#$i")
            }
            speakingId = id
        }
        if (ready) {
            run()
            return
        }
        pending = run
        if (tts == null) {
            tts = TextToSpeech(app) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId?.endsWith("#last") == true) speakingId = null
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { speakingId = null }
                    })
                    pending?.invoke()
                    pending = null
                } else {
                    tts = null
                    speakingId = null
                    Toast.makeText(app, "手机上没有可用的语音朗读引擎（设置 → DeepSeek API Key 里可以改用 DeepSeek 语音）", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun stop() {
        stopDs()
        tts?.stop()
        speakingId = null
    }

    /** 去掉 Markdown 符号，代码和公式不读。 */
    fun plain(md: String): String = md
        .replace(Regex("```[\\s\\S]*?```"), "（代码略）")
        .replace(Regex("\\$\\$[\\s\\S]*?\\$\\$"), "（公式略）")
        .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("(?m)^\\s*\\|?\\s*:?-{2,}.*$"), "")
        .replace(Regex("[#*_`>|~]"), "")
        .replace(Regex("\\n{2,}"), "\n")
        .trim()
}
