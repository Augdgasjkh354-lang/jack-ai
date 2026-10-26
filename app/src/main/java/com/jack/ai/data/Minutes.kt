package com.jack.ai.data

/** 录音纪要、「用 Kavi 总结」替用户发的那句话：说清要转文字、怎么整理。 */
object Minutes {
    /** 录完音：转文字 → 纪要；和客户谈的顺手记进客户表。 */
    fun recording(name: String, seconds: Long): String =
        "这是我刚录的一段录音（「$name」，约 ${AudioPcm.stamp(seconds)}）。请：\n" +
            "1. 用 convert_file（action=transcribe）把它转成文字稿；\n" +
            "2. 整理成纪要：一句话说清是什么事；要点（按先后）；定下来的事；待办（谁、做什么、什么时候）；提到的数字、价格、日期单独列出来；\n" +
            "3. 听得出是在和客户谈，就把客户在意什么、顾虑什么、下一步是什么，照「销售：记客户」的做法记进客户表（先认人，拿不准就问我）；\n" +
            "4. 待办里有具体时间的，问我要不要设提醒。\n" +
            "听不清、拿不准的地方标出来，不要编；文字稿不用整段贴回来。"

    /** 分享进来的视频链接、视频或录音文件：取字幕或转文字，再总结。 */
    fun summary(text: String?, hasFile: Boolean): String {
        if (hasFile) return "帮我总结这个文件讲了什么：先用 convert_file（action=transcribe）转成文字，再总结。\n" + SUMMARY_FORMAT +
            text?.takeIf { it.isNotBlank() }?.let { "\n\n分享时带的文字：\n" + it.take(1000) }.orEmpty()
        val link = text?.let { Regex("""https?://[^\s，。、"'<>]+""").find(it)?.value }
        return "帮我总结这个分享的内容。" + (if (link != null) "链接：$link\n" else "\n") +
            "做法：用浏览器打开它。是视频的话，先 extract what=subtitles 取字幕；没有字幕就 download_media audio_only=true 只存声音（存对话文件夹就行），" +
            "再 convert_file action=transcribe 转文字。不是视频就 extract what=article 读正文。\n" + SUMMARY_FORMAT +
            "\n\n分享的原文：\n" + text.orEmpty().take(2000)
    }

    private const val SUMMARY_FORMAT = "总结写成：一句话说清讲了什么；要点（按先后，视频带大概的时间点）；里面有用的数字、方法、清单单独列出来；" +
        "最后一句说值不值得完整看、适合谁看。字幕或文字稿不要整段贴回来。"
}
