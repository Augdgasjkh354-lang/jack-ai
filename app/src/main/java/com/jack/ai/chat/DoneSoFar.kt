package com.jack.ai.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 被打断的那条回答已经做过哪些「往外做」的事（发邮件、写文件、加日程、点网页……），接着做时告诉模型，免得再做一遍。
 * 出错的回答不会作为历史发回给模型（见 PromptBuilder.history），它看不到自己上次已经把邮件发出去了；只看清单的话，
 * 清单还没来得及更新的那一步就会重做。没有结果的（做到一半被停）写「结果不明」，要它先查清再做。
 */
object DoneSoFar {
    /** 只读的工具：重做没坏处，不列。 */
    private val READ_ONLY = setOf(
        "web_search", "read_webpage", "read_file", "list_emails", "read_email", "notion_search", "notion_read", "search_my_data",
        "facts", "query_trains", "update_plan", "use_skill", "self_check", "list_events", "query_table", "check_page", "search_code",
        "file_history", "my_templates", "get_goal", "check_ai_tone", "check_rewrite", "read_table_image", "run_code",
    )
    const val MAX = 30

    fun note(trail: List<String>): String {
        val results = HashMap<String, String>()
        val calls = mutableListOf<Triple<String, String, String>>() // id, 名字, 参数
        for (raw in trail) {
            val o = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: continue
            val role = (o["role"] as? JsonPrimitive)?.content
            if (role == "tool") {
                val id = (o["tool_call_id"] as? JsonPrimitive)?.content ?: continue
                results[id] = (o["content"] as? JsonPrimitive)?.content.orEmpty()
            } else if (role == "assistant") (o["tool_calls"] as? JsonArray)?.forEach { c ->
                val co = c as? JsonObject ?: return@forEach
                val fn = co["function"] as? JsonObject ?: return@forEach
                val name = (fn["name"] as? JsonPrimitive)?.content ?: return@forEach
                if (name in READ_ONLY) return@forEach
                calls += Triple((co["id"] as? JsonPrimitive)?.content.orEmpty(), name, (fn["arguments"] as? JsonPrimitive)?.content.orEmpty())
            }
        }
        if (calls.isEmpty()) return ""
        return buildString {
            append("\n上次被打断前已经做过这些会改动东西的操作（别重做；「结果不明」的先查清楚做没做成，查不清就别做，告诉用户）：\n")
            calls.takeLast(MAX).forEach { (id, name, args) ->
                val r = results[id]
                append("- ").append(name).append(' ').append(args.replace('\n', ' ').take(160)).append(" → ")
                append(if (r == null) "结果不明（做到一半被打断）" else r.replace('\n', ' ').take(160)).append('\n')
            }
        }
    }
}
