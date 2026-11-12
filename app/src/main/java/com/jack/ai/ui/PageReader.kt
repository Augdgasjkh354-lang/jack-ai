package com.jack.ai.ui

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * 浏览器里「朗读这页」：用手机自带的语音引擎把网页正文一段段念出来。
 * 切到后台、锁屏照样念（ReadAloudService 保活，锁屏和耳机能暂停、继续、停）。
 * 暂停记住念到哪一段，继续从那一段开头接着念。
 */
object PageReader {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: (() -> Unit)? = null
    private lateinit var app: Context

    /** 在念的网页标题（null = 没在念）。 */
    var title by mutableStateOf<String?>(null)
        private set
    var paused by mutableStateOf(false)
        private set
    /** 念到第几段、一共几段。 */
    var index by mutableIntStateOf(0)
        private set
    var total by mutableIntStateOf(0)
        private set
    /** 语速。 */
    var speed by mutableFloatStateOf(1f)
        private set
    private var parts: List<String> = emptyList()
    private var locale: Locale = Locale.CHINA
    /** 每次重新排队加一，旧队列的回调就认不出来了（停下、换篇时不会把状态改乱）。 */
    private var round = 0

    val active get() = title != null

    fun start(ctx: Context, pageTitle: String, text: String) {
        val list = chunks(text)
        if (list.isEmpty()) return
        app = ctx.applicationContext
        Speaker.stop()
        parts = list
        locale = localeFor(text)
        title = pageTitle.ifBlank { "网页" }
        index = 0
        total = list.size
        paused = false
        withTts { speakFrom(0) }
        com.jack.ai.ReadAloudService.start(app)
    }

    fun pause() {
        if (!active || paused) return
        paused = true
        round++
        tts?.stop()
        com.jack.ai.ReadAloudService.refresh()
    }

    fun resume() {
        if (!active || !paused) return
        paused = false
        withTts { speakFrom(index) }
        com.jack.ai.ReadAloudService.refresh()
    }

    fun toggle() = if (paused) resume() else pause()

    /** 换语速：1 → 1.25 → 1.5 → 2 → 0.75 → 1。正在念就从这一段重念。 */
    fun nextSpeed() {
        speed = SPEEDS[(SPEEDS.indexOf(speed) + 1).mod(SPEEDS.size)]
        if (active && !paused) withTts { speakFrom(index) }
    }

    fun stop() {
        round++
        tts?.stop()
        title = null
        paused = false
        parts = emptyList()
        com.jack.ai.ReadAloudService.stop()
    }

    private fun speakFrom(from: Int) {
        val t = tts ?: return
        val r = ++round
        t.stop()
        t.language = locale
        t.setSpeechRate(speed)
        for (i in from until parts.size) {
            t.speak(parts[i], if (i == from) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "$r#$i")
        }
    }

    /** 语音引擎的回调在别的线程，状态交给主线程改。 */
    private fun onMain(block: () -> Unit) = android.os.Handler(android.os.Looper.getMainLooper()).post(block)

    private fun withTts(run: () -> Unit) {
        if (ready) { run(); return }
        pending = run
        if (tts != null) return
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        val (r, i) = parse(utteranceId) ?: return
                        onMain { if (r == round) { index = i; com.jack.ai.ReadAloudService.refresh() } }
                    }
                    override fun onDone(utteranceId: String?) {
                        val (r, i) = parse(utteranceId) ?: return
                        onMain { if (r == round && i == parts.lastIndex) stop() }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        val (r, _) = parse(utteranceId) ?: return
                        onMain { if (r == round) stop() }
                    }
                })
                pending?.invoke()
                pending = null
            } else {
                tts = null
                pending = null
                onMain {
                    stop()
                    Toast.makeText(app, "手机上没有可用的语音朗读引擎", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun parse(id: String?): Pair<Int, Int>? {
        val r = id?.substringBefore('#')?.toIntOrNull() ?: return null
        val i = id.substringAfter('#').toIntOrNull() ?: return null
        return r to i
    }

    val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)

    /** 去掉 Markdown 符号后按段落切，太长的段再按句子切，每段不超过 [max] 字（暂停后从段头接着念，段短一点少重复）。 */
    fun chunks(text: String, max: Int = 400): List<String> {
        val out = mutableListOf<String>()
        Speaker.plain(text).split('\n').map { it.trim() }.filter { p -> p.any { it.isLetterOrDigit() } }.forEach { para ->
            if (para.length <= max) { out += para; return@forEach }
            val sb = StringBuilder()
            Regex("[^。！？!?；;.]+[。！？!?；;.]*").findAll(para).map { it.value }.forEach { s ->
                if (sb.isNotEmpty() && sb.length + s.length > max) { out += sb.toString().trim(); sb.clear() }
                // 一句话本身就超长（没有标点的）：硬切
                s.chunked(max).forEach { piece ->
                    if (sb.length + piece.length > max) { out += sb.toString().trim(); sb.clear() }
                    sb.append(piece)
                }
            }
            if (sb.isNotBlank()) out += sb.toString().trim()
        }
        return out.filter { it.isNotBlank() }
    }

    /** 汉字很少、拉丁字母多的按英文念，不然按中文念。 */
    fun localeFor(text: String): Locale {
        val sample = text.take(3000)
        val han = sample.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        val latin = sample.count { it in 'a'..'z' || it in 'A'..'Z' }
        return if (han * 10 < latin) Locale.US else Locale.CHINA
    }
}
