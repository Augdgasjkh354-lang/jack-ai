package com.jack.ai.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 上下文压缩。做法参考 DeepSeek 官方 deepseek-harness 的 compaction-basic：
 * 最近一段原文保留（约阈值的 16%），更早的内容写成分栏提要；再次压缩时把旧提要合并进去。
 */
object Compression {

    /** 粗略估算 token：中文约 0.7 token/字，其他字符约 0.3 token/字，图片按 1024 计。 */
    fun estimateTokens(text: String): Int {
        var cjk = 0
        var other = 0
        for (ch in text) if (ch.code in 0x2E80..0x9FFF || ch.code in 0xF900..0xFAFF) cjk++ else other++
        return (cjk * 0.7 + other * 0.3).toInt() + 4
    }

    fun estimateTokens(m: ChatMessage): Int =
        estimateTokens(m.content) + estimateTokens(m.reasoning) / 4 +
            m.attachments.sumOf { if (it.kind == "image") 1024 else estimateTokens(it.text) } +
            m.trail.sumOf { estimateTokens(it) }

    /** 当前还在“原文区”的消息（提要之后的）。 */
    fun liveMessages(conv: Conversation): List<ChatMessage> {
        val until = conv.summaryUntilId ?: return conv.messages
        val idx = conv.messages.indexOfFirst { it.id == until }
        return if (idx < 0) conv.messages else conv.messages.drop(idx + 1)
    }

    /** 已经被压缩进提要的消息。 */
    fun compressedMessages(conv: Conversation): List<ChatMessage> {
        val until = conv.summaryUntilId ?: return emptyList()
        val idx = conv.messages.indexOfFirst { it.id == until }
        return if (idx < 0) emptyList() else conv.messages.take(idx + 1)
    }

    /**
     * 找到切分点：从最新往前保留约 retainTokens 的原文，且至少保留最近 2 轮，
     * 切在“用户消息之前”，不把一问一答拆开。返回要压缩的消息（可能为空）。
     */
    fun toCompress(conv: Conversation, retainTokens: Int): List<ChatMessage> {
        val live = liveMessages(conv).filter { it.error == null || it.content.isNotBlank() }
        val userIdx = live.indices.filter { live[it].role == "user" }
        if (userIdx.size < 3) return emptyList()
        var kept = 0
        var cut = userIdx[userIdx.size - 2] // 至少保留最近 2 轮
        for (i in live.indices.reversed()) {
            kept += estimateTokens(live[i])
            if (i <= cut && live[i].role == "user") {
                cut = i
                if (kept >= retainTokens) break
            }
        }
        return if (cut <= 0) emptyList() else live.take(cut)
    }

    private const val INSTRUCTIONS = """你的任务是为一段很长的聊天记录写一份“前情提要”，供 AI 在之后的对话中当作记忆使用。原始记录会被这份提要替换，所以凡是之后可能用得上的信息，都要写进去。

按下面的栏目，用简体中文写成简洁的条目，栏目标题保持不变，没有内容的栏目写“（无）”：

## 对话主线
这段对话主要在做什么、用户的目的是什么。

## 话题与结论
按时间顺序列出聊过的每个话题，以及最后得出的结论或决定。

## 关键事实与细节
人名、地名、数字、日期、金额、设定、专有名词、书名链接等，原样保留，不要改写或取整。用户说过的重要原话用引号保留。

## 用户的要求与纠正
用户提出的长期要求（格式、语气、篇幅、称呼等），以及纠正过 AI 的地方，必须准确记录。

## 图片与文件
用户发过的图片画了什么、文件讲了什么，写出对后续有用的要点。

## 还没完成的事
尚未回答的问题、答应过要做但还没做的事、悬而未决的讨论。

## 最近在聊什么
这段记录结束时正在聊的内容和当时的状态，越具体越好，方便无缝接上。

规则：
- 只写记录里真实出现过的内容，不要推测或补充。
- 宁可多写细节，也不要漏掉之后可能被问到的信息。
- 如果给了“之前的提要”，把它和新记录合并成一份完整的新提要：仍然成立的保留，过时或被推翻的删除或更新，不要原样照抄。
- 不要提到“压缩”“提要”这件事本身，直接输出各栏内容。"""

    /** 生成压缩请求。图片原样发给模型（Flash 能看图），让它写成文字描述。 */
    fun buildRequest(prevSummary: String, msgs: List<ChatMessage>, imagesOk: Boolean): List<JsonObject> {
        val parts = buildJsonArray {
            fun text(t: String) = add(buildJsonObject { put("type", "text"); put("text", t) })
            if (prevSummary.isNotBlank()) text("【之前的提要】\n$prevSummary\n\n【之后的新记录】\n")
            else text("【聊天记录】\n")
            val sb = StringBuilder()
            for (m in msgs) {
                if (m.error != null && m.content.isBlank()) continue
                val who = if (m.role == "user") "用户" else "AI"
                sb.append("\n— $who：")
                m.attachments.filter { it.kind == "file" }.forEach { f ->
                    sb.append("\n[文件：${f.name}]\n${f.text.take(20_000)}\n[文件结束]\n")
                }
                val imgs = m.attachments.filter { it.kind == "image" }
                if (imgs.isNotEmpty()) {
                    if (imagesOk) {
                        sb.append("\n[用户发了 ${imgs.size} 张图片，见下]\n")
                        text(sb.toString()); sb.clear()
                        imgs.forEach { img ->
                            val url = Attachments.imageDataUrl(img.path) ?: return@forEach
                            add(buildJsonObject {
                                put("type", "image_url")
                                put("image_url", buildJsonObject { put("url", url); put("detail", "low") })
                            })
                        }
                    } else {
                        sb.append("\n[用户发了 ${imgs.size} 张图片]\n")
                    }
                }
                if (m.searches.isNotEmpty()) {
                    sb.append("\n[AI 联网查了：${m.searches.joinToString("；") { it.query }}]\n")
                }
                sb.append(m.content).append('\n')
            }
            if (sb.isNotEmpty()) text(sb.toString())
            text("\n请按要求写出完整的前情提要。")
        }
        return listOf(
            buildJsonObject { put("role", "system"); put("content", INSTRUCTIONS) },
            buildJsonObject { put("role", "user"); put("content", parts) },
        )
    }

    /** 在已压缩的原文里按关键词找相关片段（“翻旧账”工具）。 */
    fun searchHistory(msgs: List<ChatMessage>, query: String): String {
        if (msgs.isEmpty()) return "没有更早的记录。"
        val terms = query.split(Regex("[\\s,，、;；。.!！?？]+")).map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf(query.trim()) }
        fun score(m: ChatMessage): Int {
            val text = m.content + m.attachments.joinToString(" ") { it.name + " " + it.text.take(5000) }
            var s = 0
            for (t in terms) {
                if (text.contains(t, ignoreCase = true)) s += 3
                else if (t.length >= 4) {
                    // 中文长词拆成两字片段做模糊匹配
                    s += t.windowed(2).count { text.contains(it, ignoreCase = true) }.coerceAtMost(2)
                }
            }
            return s
        }
        val scored = msgs.mapIndexed { i, m -> i to score(m) }.filter { it.second > 0 }
            .sortedByDescending { it.second }.take(6)
        if (scored.isEmpty()) return "旧记录里没有找到和“$query”相关的内容。"
        val picked = scored.flatMap { (i, _) -> listOf(i - 1, i, i + 1) }
            .filter { it in msgs.indices }.distinct().sorted()
        return picked.joinToString("\n\n") { i ->
            val m = msgs[i]
            val who = if (m.role == "user") "用户" else "AI"
            "#${i + 1} $who：${m.content.take(1500)}"
        }
    }
}
