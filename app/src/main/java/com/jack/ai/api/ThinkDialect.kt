package com.jack.ai.api

import com.jack.ai.data.Endpoint
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 各家「思考开 / 关」的参数不一样（OpenAI 格式里只认 reasoning_effort 的不算）：
 * - 百炼的 Qwen3 混合思考模型、百炼上的 Kimi：`enable_thinking: true/false`。开源 Qwen3 默认开思考，非流式请求不发 false 还会报错。
 * - Kimi 官方的 K2.5 / K2.6：`thinking: {"type": "enabled" | "disabled"}`，默认开。
 * 以前这几种一律不发，选「思考关」照样在想（多等、多花思考的钱），后台任务也关不掉。
 * 只认得出的才发，认不出的照旧（发了对方不认的参数会报错）。
 */
object ThinkDialect {
    enum class Kind { NONE, ENABLE_THINKING, KIMI }

    fun of(ep: Endpoint): Kind {
        if (ep.deepseek) return Kind.NONE
        val host = runCatching { java.net.URI(ep.baseUrl).host.orEmpty() }.getOrDefault("").lowercase()
        val m = ep.model.lowercase().substringAfterLast('/')
        val kimiHybrid = Regex("""k2[.\-_]?[56]""").containsMatchIn(m) && "kimi" in m
        if ("dashscope" in host || host.endsWith("aliyuncs.com")) {
            if (kimiHybrid) return Kind.ENABLE_THINKING
            // Qwen3 混合思考：qwen3-xxx（只思考的 -thinking、不思考的 -instruct、编程、语音、翻译这些不算）和商用的 plus / max / flash / turbo
            val qwen3 = m.startsWith("qwen3") && listOf("instruct", "thinking", "coder", "embedding", "tts", "asr", "mt-", "omni").none { it in m }
            val commercial = Regex("""^qwen-(plus|max|flash|turbo)(-latest|-\d{4}-\d{2}-\d{2})?$""").matches(m)
            return if (qwen3 || commercial) Kind.ENABLE_THINKING else Kind.NONE
        }
        if (("moonshot" in host || "kimi" in host) && kimiHybrid) return Kind.KIMI
        return Kind.NONE
    }

    /** 把思考开关写进请求体。 */
    fun put(body: JsonObjectBuilder, kind: Kind, on: Boolean) {
        when (kind) {
            Kind.ENABLE_THINKING -> body.put("enable_thinking", on)
            Kind.KIMI -> body.put("thinking", buildJsonObject { put("type", if (on) "enabled" else "disabled") })
            Kind.NONE -> {}
        }
    }
}
