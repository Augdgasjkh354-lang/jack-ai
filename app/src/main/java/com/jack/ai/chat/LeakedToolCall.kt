package com.jack.ai.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 回复正文里漏出来的工具调用：服务偶尔没把模型的工具调用解析出来，调用标记原样当文字回来了（DeepSeek 的
 * 「<｜tool▁calls▁begin｜>」「<｜DSML｜…」这类）。这不是回答，不能当回答交给用户，也不能当它做过了。
 *
 * 只认常见的调用标记，而且标记后面要出现这一轮给过的某个工具名（整词）：回答里讲这些格式本身不算。只判断，不解析也不执行。
 * 来自 Mika 的 LeakedToolCall（core/agent/LeakedToolCall.kt），Apache-2.0；Jack AI modification：加了 DeepSeek DSML 标记和退回、收尾的话。
 */
internal object LeakedToolCall {
    private val MARKERS = listOf(
        "<tool_call>", "<function=", "<function_calls>", "<invoke name=",
        "<|python_tag|>", "[TOOL_CALLS]",
        "<｜tool▁calls▁begin｜>", "<｜tool▁call▁begin｜>", "<｜DSML｜",
        "<|tool_calls_section_begin|>", "<|tool_call_begin|>",
    )

    const val NOTICE = "（这一步模型把工具调用写成了文字，没有真的执行。可以点「重新回答」再试一次。）"

    private fun start(text: String): Int? = MARKERS.mapNotNull { m -> text.indexOf(m).takeIf { it >= 0 } }.minOrNull()

    /** [text] 里漏出了对 [toolNames] 中某个工具的调用时返回那个工具名；没有为 null。只看第一个调用标记之后的工具名。 */
    fun find(text: String, toolNames: Collection<String>): String? {
        val start = start(text) ?: return null
        return toolNames.filter { it.isNotEmpty() }
            .mapNotNull { name -> Regex("(?<![A-Za-z0-9_])" + Regex.escape(name) + "(?![A-Za-z0-9_])").find(text, start)?.let { name to it.range.first } }
            .minByOrNull { it.second }?.first
    }

    /** 调用标记前面的正常话（再漏一次时留给用户看）。 */
    fun before(text: String): String = start(text)?.let { text.substring(0, it) } ?: text

    fun retry(tool: String) =
        "【系统】你上一条把 $tool 的工具调用写成了正文，它没有被执行。请通过工具调用接口重新调用（不要在正文里写调用标记）；如果其实不需要工具，就直接用文字回答。"
}

/** 这一轮给模型的工具名。 */
internal fun toolNames(tools: JsonArray): List<String> = tools.mapNotNull {
    ((it as? JsonObject)?.get("function") as? JsonObject)?.get("name")?.let { n -> (n as? JsonPrimitive)?.content }
}
