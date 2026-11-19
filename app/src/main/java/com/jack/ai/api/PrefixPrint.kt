package com.jack.ai.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 请求开头的指纹：DeepSeek 的缓存从请求开头逐字比，开头哪一段变了，从那里往后就都命中不了。
 * 按请求的顺序分成四段各记一个短哈希：模型和思考开关、系统提示词、工具说明、第一条用户消息（对话背景在它前面）。
 * 用量页拿相邻两次请求比，就知道没命中是哪一段变了，还是开头没变、DeepSeek 那边没找到缓存。
 */
object PrefixPrint {
    val PARTS = listOf("模型或思考深浅", "系统提示词", "工具说明", "对话开头（背景、记忆、第一条消息）")

    fun of(model: String, thinking: String?, messages: List<JsonObject>, tools: JsonArray?): String {
        fun role(m: JsonObject) = (m["role"] as? JsonPrimitive)?.content
        val system = messages.takeWhile { role(it) == "system" }.joinToString("\n") { it.toString() }
        val firstUser = messages.firstOrNull { role(it) == "user" }?.toString().orEmpty()
        return listOf("$model|$thinking", system, tools?.toString().orEmpty(), firstUser).joinToString("-") { h(it) }
    }

    private fun h(s: String): String = Integer.toHexString(s.hashCode()).padStart(8, '0').takeLast(6)

    /**
     * 两次请求比：第一个不一样的段（看得出的原因）；都一样返回 null。
     * 指纹缺了（旧记录、别的服务）返回 ""。
     */
    fun firstChange(before: String?, after: String?): String? {
        if (before.isNullOrBlank() || after.isNullOrBlank()) return ""
        val a = before.split('-'); val b = after.split('-')
        if (a.size != b.size) return ""
        val i = a.indices.firstOrNull { a[it] != b[it] } ?: return null
        return PARTS.getOrElse(i) { "" }
    }
}
