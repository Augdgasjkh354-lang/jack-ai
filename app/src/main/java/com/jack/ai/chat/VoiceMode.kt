package com.jack.ai.chat

/** 正在语音对话的那个对话（见 ui/VoiceChat）：回答会被读出来，提示 Kavi 说短一点、别用排版。 */
object VoiceMode {
    @Volatile var on = false
    /** 在哪个对话里说的；还没发第一句的新对话是 null（那时谁先开始回答就是它）。 */
    @Volatile var convId: String? = null

    const val NOTE = "【Voice conversation】{user} is talking to you by voice and will hear your reply read aloud. " +
        "Answer like speaking: short, natural sentences, the key point first; no Markdown, tables, lists, code or links unless {user} asks. " +
        "If the job needs tools or a long result, still do it, then say the outcome in a sentence or two.\n"

    fun note(convId: String): String = if (on && (this.convId == null || this.convId == convId)) NOTE else ""
}
