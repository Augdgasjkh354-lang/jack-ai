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
import kotlinx.coroutines.channels.Channel
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

    /** 排队读的会话：enqueue 一句读一句，finish 后才算读完。 */
    private var queueId: String? = null
    private var queueChannel: Channel<String>? = null
    private var queueJob: Job? = null
    /** 一共排了几句。 */
    private var queueTotal = 0
    /** finish() 时一共排了几句，-1 表示还没 finish；手机朗读读到这一句就收尾。 */
    private var queueLast = -1
    /** 手机朗读已经交给引擎的句子编号。 */
    private var queuePhoneIndex = 0
    /** 手机朗读已经读完的句子编号。 */
    private var queuePhoneDone = -1
    /** 手机朗读最后那句的编号（finish 后、读完最后一句时才定，-1 表示还没定）。 */
    private var queuePhoneLast = -1
    /** 手机朗读还没初始化时攒着的句子，初始化好按顺序读。 */
    private val queuePhonePending = mutableListOf<String>()
    /** 攒着的句子是哪条消息的（排队任务可能已经收完句子结束了，引擎这时才建好）。 */
    private var queuePhonePendingId: String? = null

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
        stop()
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

    /**
     * 往同一条消息后面排一句读（边写边读用）：第一句来就开始读，speakingId 马上变成这条消息。
     * 已经排过队就算读过，stop() 会丢掉还没读的。DeepSeek 语音按句子流式合成、接着往
     * 同一个 AudioTrack 写；手机朗读用 QUEUE_ADD。finish(id) 之后、所有句子读完，
     * speakingId 才变回 null。
     */
    fun enqueue(ctx: Context, id: String, sentence: String) {
        if (sentence.isBlank()) return
        if (queueId != id) {
            stop()
            val app = ctx.applicationContext
            queueId = id
            queueTotal = 0
            queueLast = -1
            queuePhoneIndex = 0
            queuePhoneDone = -1
            queuePhoneLast = -1
            speakingId = id
            val channel = Channel<String>(Channel.UNLIMITED)
            queueChannel = channel
            queueJob = scope.launch { runQueue(app, id, channel) }
        }
        queueTotal++
        queueChannel?.trySend(sentence)
    }

    /** 这条消息的回答写完了：排队的句子读完就收尾；一句都没排过就直接收尾。 */
    fun finish(id: String) {
        if (queueId != id) return
        queueLast = queueTotal - 1
        if (queueLast < 0) speakingId = null
        queueChannel?.close()
    }

    /** 排队读的后台任务：先看设置定用哪种朗读，再一句句接着读，直到 finish 关掉 channel。 */
    private suspend fun runQueue(app: Context, id: String, channel: Channel<String>) {
        val st = withContext(Dispatchers.IO) { runCatching { Store(app).loadSettings() }.getOrNull() }
        try {
            if (st != null && st.dsVoice && st.apiKey.isNotBlank()) {
                // 联网合成、往 AudioTrack 写都会卡住线程，不能在主线程上跑
                withContext(Dispatchers.IO) { speakDsQueued(app, st.apiKey, st.dsVoiceId, Store(app), channel, id) }
            } else {
                for (sentence in channel) speakPhoneQueued(app, id, sentence)
                // 手机朗读：最后一句读完由监听收尾；读完才收到的 finish 在这里收尾（引擎还没建好时攒着的也算）
                queuePhoneLast = queuePhoneIndex + queuePhonePending.size - 1
                if (speakingId == id && queuePhoneDone >= queuePhoneLast) speakingId = null
            }
        } finally {
            if (queueId == id) { queueId = null; queueChannel = null; queueJob = null }
        }
    }

    /** DeepSeek TTS（排队读）：建一次 AudioTrack，句子一个个合成、接着往里写。 */
    private suspend fun speakDsQueued(app: Context, key: String, voice: String, store: Store, channel: Channel<String>, id: String) = kotlinx.coroutines.coroutineScope {
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
        var failed = false
        try {
            t.play()
            var written = 0L
            for (sentence in channel) {
                // 手机朗读的引擎和攒着的句子都只在主线程上碰
                if (failed) { withContext(Dispatchers.Main) { speakPhoneQueued(app, id, sentence) }; continue }
                try {
                    for (chunk in DeepSeekTools.ttsChunks(sentence)) {
                        if (!isActive) break
                        DeepSeekTools.ttsStream(key, chunk, voice).use { input ->
                            store.addPaidCall(DeepSeekTools.TTS_KEY, DeepSeekTools.ttsChars(chunk) * DeepSeekTools.TTS_PRICE_PER_CHAR)
                            val buf = ByteArray(8192)
                            var odd = -1
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
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    if (!isActive) break
                    failed = true
                    withContext(Dispatchers.Main) {
                        Toast.makeText(app, (e.message ?: "DeepSeek 朗读失败") + "，改用手机自带的朗读", Toast.LENGTH_LONG).show()
                        speakPhoneQueued(app, id, sentence)
                    }
                }
            }
            // 等缓冲里的声音放完再结束；已经改用手机朗读的，收尾交给手机朗读的监听
            if (isActive && !failed) {
                val frames = (written / 2).toInt()
                while (isActive && t.playbackHeadPosition < frames && t.playState == android.media.AudioTrack.PLAYSTATE_PLAYING) kotlinx.coroutines.delay(100)
            }
            if (failed) withContext(Dispatchers.Main) {
                // 改用手机朗读后：手机那边的最后一句就是最后交给引擎的那句（引擎还没建好时攒着的也算）
                queuePhoneLast = queuePhoneIndex + queuePhonePending.size - 1
                if (speakingId == id && queuePhoneDone >= queuePhoneLast) speakingId = null
            }
        } finally {
            runCatching { t.stop() }
            t.release()
            if (track === t) track = null
            if (!failed && speakingId == id) speakingId = null
        }
    }

    /** 手机朗读（排队读）：一句一个编号，QUEUE_ADD 接着读；没初始化好先攒着。 */
    private fun speakPhoneQueued(app: Context, id: String, sentence: String) {
        if (!ready) {
            queuePhonePendingId = id
            queuePhonePending += sentence
            ensureTts(app)
            return
        }
        val index = queuePhoneIndex++
        tts?.speak(sentence, TextToSpeech.QUEUE_ADD, null, "$id#q$index")
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
        queueJob?.cancel()
        queueJob = null
        // stop() 会叫醒正卡在 write() 里的线程，pause() 不会
        track?.let { runCatching { it.stop(); it.flush() } }
    }

    /** 手机朗读引擎没建好就建一个；建好了先把攒着的句子按顺序读出来。 */
    private fun ensureTts(app: Context) {
        if (tts != null) return
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId?.endsWith("#last") == true) { speakingId = null; return }
                        // 排队读的句子：读到手机那边最后一句才收尾
                        val index = utteranceId?.substringAfter("#q", "")?.toIntOrNull() ?: return
                        queuePhoneDone = index
                        if (queuePhoneLast >= 0 && index >= queuePhoneLast) speakingId = null
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { speakingId = null }
                })
                pending?.invoke()
                pending = null
                val id = queuePhonePendingId
                queuePhonePendingId = null
                val waiting = queuePhonePending.toList()
                queuePhonePending.clear()
                // 没被 stop 掉的才读（stop 会把它们清空）
                if (id != null && speakingId == id) waiting.forEach { speakPhoneQueued(app, id, it) }
            } else {
                tts = null
                speakingId = null
                Toast.makeText(app, "手机上没有可用的语音朗读引擎（设置 → DeepSeek API Key 里可以改用 DeepSeek 语音）", Toast.LENGTH_LONG).show()
            }
        }
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
        ensureTts(app)
    }

    fun stop() {
        stopDs()
        tts?.stop()
        speakingId = null
        queueId = null
        queueChannel?.close()
        queueChannel = null
        queueTotal = 0
        queueLast = -1
        queuePhoneIndex = 0
        queuePhoneDone = -1
        queuePhoneLast = -1
        queuePhonePending.clear()
        queuePhonePendingId = null
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
