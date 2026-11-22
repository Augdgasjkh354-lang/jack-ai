package com.jack.ai.tools

import com.jack.ai.data.DraftCard
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 发帖草稿卡片（show_draft）：写好的小红书、朋友圈、微信消息按平台发出去的样子显示在聊天里，下面能复制、去发、改一下。
 * 思路来自 assistant-ui/tool-ui 的帖子预览组件（MIT），界面是自己写的。只是预览，不替用户发。
 */
internal fun ToolSession.showDraft(args: JsonObject): String {
    val (card, warns) = parseDraft(args) { name -> runCatching { wsFile(name).absolutePath }.getOrNull() }
        .getOrElse { return it.message.orEmpty() }
    if (!ui.draft(card)) return "这里显示不了草稿卡片，直接把草稿写在回答里。"
    succeeded.incrementAndGet()
    return "草稿卡片已经显示在你这条回答下面（${PLATFORMS[card.platform]}的样子），用户能直接复制、去发或让你改。" +
        (if (warns.isEmpty()) "" else "\n注意：" + warns.joinToString("；") + "。要改就改好再调一次（会换掉旧卡片）。")
}

internal val PLATFORMS = mapOf("xhs" to "小红书", "moments" to "朋友圈", "wechat" to "微信消息", "weibo" to "微博", "douyin" to "抖音", "gzh" to "公众号", "other" to "帖子")

/** 拆出草稿并检查各平台的限制：超了不拦，提醒 Kavi。[resolve] 把文件名换成完整路径，找不到返回 null。 */
internal fun parseDraft(args: JsonObject, resolve: (String) -> String?): Result<Pair<DraftCard, List<String>>> = runCatching {
    val platform = args["platform"].text().trim().lowercase().takeIf { it in PLATFORMS } ?: "other"
    val body = args["body"].text().trim()
    require(body.isNotEmpty()) { "show_draft 要写 body：要贴出去的正文。" }
    val title = args["title"].text().trim()
    val tags = (args["tags"] as? JsonArray).orEmpty().map { it.text().trim().trimStart('#', '＃').trim() }.filter { it.isNotEmpty() }.distinct()
    val warns = mutableListOf<String>()
    val names = (args["images"] as? JsonArray).orEmpty().map { it.text().trim() }.filter { it.isNotEmpty() }
    val images = names.mapNotNull { n -> resolve(n).also { if (it == null) warns += "对话文件夹里没有「$n」" } }
    val chars = body.count { !it.isWhitespace() }
    fun width(s: String) = s.sumOf { if (it.code < 0x2E80) 0.5 else 1.0 }
    when (platform) {
        "xhs" -> {
            if (title.isEmpty()) warns += "小红书笔记最好有标题"
            if (width(title) > 20) warns += "标题超过 20 个字，小红书会截掉"
            if (chars > 1000) warns += "正文 $chars 字，小红书上限 1000 字"
            if (images.isEmpty()) warns += "小红书笔记至少要一张图"
            if (images.size > 18) warns += "图片 ${images.size} 张，小红书最多 18 张"
            if (tags.size > 10) warns += "话题 ${tags.size} 个，一般 3～8 个就够"
        }
        "moments" -> if (images.size > 9) warns += "朋友圈最多 9 张图"
        "weibo" -> if (chars > 2000) warns += "正文 $chars 字，超过 2000 字微博会折叠成长文"
        "douyin" -> if (width(title + body) > 1000) warns += "抖音作品描述最多 1000 字"
    }
    DraftCard(platform, title, body, tags, images.take(18), args["to"].text().trim()) to warns
}

/** 复制出去的全文：标题、正文、话题。 */
fun draftText(d: DraftCard): String = buildString {
    if (d.title.isNotEmpty()) append(d.title).append("\n\n")
    append(d.body)
    if (d.tags.isNotEmpty()) append("\n\n").append(d.tags.joinToString(" ") { "#$it" })
}
