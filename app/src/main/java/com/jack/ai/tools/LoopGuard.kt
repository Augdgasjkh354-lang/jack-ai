package com.jack.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 防原地打转（1017 版偶尔会反复调同一个工具却不往前走）：同样的工具、同样的参数连着调 3 次，
 * 或者一次回答里一共调 5 次（读文件除外，改完再读很正常），结果不会变，就不执行，让模型停下换方法。
 *
 * 操作手机、浏览器和连接器不一样：「往下翻」「读屏幕」「后退」每次参数都相同，结果却每次不同，
 * 按参数算会把正常的连续翻页拦掉。这几样只在连着两次结果一模一样（翻到底了、点了没反应）之后，
 * 第三次才拦；中间做了别的就重新算，不会一直拦下去。
 */
class LoopGuard {
    private var lastCall = ""
    private var streak = 0
    private val counts = HashMap<String, Int>()
    /** 有状态的工具：上一次的结果，和连着几次结果相同。 */
    private var lastResult: String? = null
    private var sameResults = 0

    /** 该拦就返回给模型看的话，否则 null。 */
    @Synchronized
    fun check(name: String, argsText: String): String? {
        val sig = name + "|" + canonicalArgs(argsText)
        if (sig != lastCall) { lastResult = null; sameResults = 0 }
        if (stateful(name)) {
            lastCall = sig
            streak = 1
            if (sameResults < 2) return null
            return "你已经连着 ${sameResults + 1} 次用相同的参数调用 $name，前 $sameResults 次的结果一模一样（没有新内容了，或者这个操作没起作用）。不要再重复：换个方法，或者直接告诉用户卡在哪里、需要什么。"
        }
        streak = if (sig == lastCall) streak + 1 else 1
        lastCall = sig
        val total = (counts[sig] ?: 0) + 1
        counts[sig] = total
        if (streak < 3 && (total < 5 || name == "read_file")) return null
        return "你已经用完全相同的参数调用 $name ${maxOf(streak, total)} 次了，结果不会变。不要再重复：换个方法，或者直接告诉用户卡在哪里、需要什么。"
    }

    /** 工具跑完后记下结果（只有有状态的工具用得上）。 */
    @Synchronized
    fun record(name: String, argsText: String, result: String) {
        if (!stateful(name) || name + "|" + canonicalArgs(argsText) != lastCall) return
        sameResults = if (result == lastResult) sameResults + 1 else 1
        lastResult = result
    }

    private fun stateful(name: String) = name == "phone" || name == "browser" || name.startsWith(com.jack.ai.data.Mcp.PREFIX)

    private fun canonicalArgs(argsText: String): String {
        val trimmed = argsText.trim()
        return runCatching {
            Json.parseToJsonElement(trimmed).sortedKeys().toString()
        }.getOrElse { trimmed }
    }

    private fun JsonElement.sortedKeys(): JsonElement = when (this) {
        is JsonObject -> JsonObject(
            entries.sortedBy { it.key }.associate { (key, value) -> key to value.sortedKeys() },
        )
        is JsonArray -> JsonArray(map { it.sortedKeys() })
        else -> this
    }
}
