package com.jack.ai.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * 长任务瘦身：Work 一长，前面几十次搜索、读网页的原文都堆在上下文里，越往后越贵，模型也容易被旧内容带偏。
 * 工具结果加起来超过 [BUDGET] 字时，把较早的大段结果压成开头一段 + 里面的链接 + 结尾（核实、失败提示在结尾），
 * 最近 [KEEP_RECENT] 个原样留着。一次压一批：压完前面就不再变，缓存照样能命中，直到下次又超。
 * 只改这次请求用的 messages，不动 trail（存档里还是原文）。
 */
object ContextSlim {
    const val BUDGET = 60_000
    const val KEEP_RECENT = 6
    /** 比这短的不压，压了也省不了多少。 */
    const val MIN_LEN = 1_500
    const val MARK = "【Trimmed to save space: an older tool result, with its start, links and end kept. Call the tool again if you need the full text.】\n"

    /** 返回压了几条。 */
    fun slim(messages: MutableList<JsonObject>): Int {
        val tools = messages.indices.filter { (messages[it]["role"] as? JsonPrimitive)?.content == "tool" }
        if (tools.sumOf { text(messages[it]).length } <= BUDGET) return 0
        var n = 0
        tools.dropLast(KEEP_RECENT).forEach { i ->
            val c = text(messages[i])
            if (c.length <= MIN_LEN || c.startsWith(MARK)) return@forEach
            messages[i] = JsonObject(messages[i] + ("content" to JsonPrimitive(shrink(c))))
            n++
        }
        return n
    }

    internal fun shrink(c: String): String {
        val head = c.take(600)
        val tail = c.takeLast(300)
        val links = com.jack.ai.tools.Sources.find(c.substring(600, c.length - 300)).distinct().take(15)
        return MARK + head + "\n…\n" + (if (links.isEmpty()) "" else "Links in the trimmed part:\n" + links.joinToString("\n") + "\n…\n") + tail
    }

    private fun text(m: JsonObject) = (m["content"] as? JsonPrimitive)?.content.orEmpty()
}
