package com.jack.ai.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * 长任务瘦身：Work 一长，前面几十次搜索、读网页的原文都堆在上下文里，越往后越贵，模型也容易被旧内容带偏。
 * 工具结果加起来超过 [BUDGET] 字时，把较早的大段结果压成开头一段 + 里面的链接 + 结尾（核实、失败提示在结尾），
 * 最近 [KEEP_RECENT] 个原样留着。一次压一批：压完前面就不再变，缓存照样能命中，直到下次又超。
 * 只改这次请求用的 messages，不动 trail（存档里还是原文）。
 * 给了 spill 就先把全文存成文件（[com.jack.ai.data.Spill]），压过的结果里写上文件名，要细看时 read_file 读回来。
 */
object ContextSlim {
    const val BUDGET = 60_000
    const val KEEP_RECENT = 6
    /** 比这短的不压，压了也省不了多少。 */
    const val MIN_LEN = 1_500
    const val MARK = "【Trimmed to save space: an older tool result, with its start, links and end kept.】\n"

    /** 返回压了几条。 */
    fun slim(messages: MutableList<JsonObject>, spill: ((String) -> String?)? = null): Int {
        val tools = messages.indices.filter { (messages[it]["role"] as? JsonPrimitive)?.content == "tool" }
        if (tools.sumOf { text(messages[it]).length } <= BUDGET) return 0
        // 最近的留着，但至少留一条：原来 dropLast(KEEP_RECENT) 在工具消息 ≤6 条时返回空，永远压不到
        val keep = minOf(KEEP_RECENT, (tools.size - 1).coerceAtLeast(1))
        var n = 0
        tools.dropLast(keep).forEach { i ->
            val c = text(messages[i])
            if (c.length <= MIN_LEN || c.startsWith(MARK)) return@forEach
            messages[i] = JsonObject(messages[i] + ("content" to JsonPrimitive(shrink(c, spill?.invoke(c)))))
            n++
        }
        return n
    }

    internal fun shrink(c: String, saved: String? = null): String {
        val head = c.take(600)
        val tail = c.takeLast(300)
        val links = com.jack.ai.tools.Sources.find(c.substring(600, c.length - 300)).distinct().take(15)
        return MARK + (saved?.let { "Full text saved as 「$it」: read it with read_file (name = $it) instead of calling the tool again.\n" } ?: "Call the tool again if you need the full text.\n") + head + "\n…\n" + (if (links.isEmpty()) "" else "Links in the trimmed part:\n" + links.joinToString("\n") + "\n…\n") + tail
    }

    private fun text(m: JsonObject) = (m["content"] as? JsonPrimitive)?.content.orEmpty()
}
