package com.jack.ai.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** 用手机自带的语音引擎朗读回答。 */
object Speaker {
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
            val app = ctx.applicationContext
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
                    Toast.makeText(app, "手机上没有可用的语音朗读引擎", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun stop() {
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
