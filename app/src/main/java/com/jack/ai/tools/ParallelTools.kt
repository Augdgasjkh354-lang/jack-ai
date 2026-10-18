package com.jack.ai.tools

import com.jack.ai.api.DeepSeekClient
import com.jack.ai.chat.Agent
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.SkillTools
import com.jack.ai.data.ThinkLevel
import com.jack.ai.data.backgroundEndpoint
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 子任务能用的工具：查资料、读文件，不能生成文件、发信、改数据。 */
private val SUB_TOOLS = setOf("web_search", "read_webpage", "read_file")

/**
 * 分头并行（Work）：把几件彼此独立的子任务同时派给“分身”去做，各自查资料、读文件，
 * 做完把结果汇总回来。分身用便宜的后台模型，每个最多 8 轮工具。
 */
internal suspend fun ToolSession.delegateTasks(args: JsonObject): String {
    val tasks = (args["tasks"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val title = o["title"].text().trim()
        val what = o["instructions"].text().trim()
        if (title.isBlank() || what.isBlank()) null else title to what
    }.take(5)
    if (tasks.isEmpty()) return "没有子任务"
    val ep = settings.backgroundEndpoint() ?: return "没有可用的模型"
    return runTool("分头做 ${tasks.size} 件事") {
        val client = DeepSeekClient()
        val folder = ContextNote.files(wsDir)
        val tools = JsonArray(listOfNotNull(
            SkillTools.WEB_SEARCH.takeIf { web }, SkillTools.READ_WEBPAGE.takeIf { web },
            SkillTools.READ_FILE,
        ))
        val results = coroutineScope {
            tasks.map { (title, what) ->
                async {
                    val agent = Agent({ msgs, t -> client.stream(ep, msgs, ThinkLevel.OFF, 1.0f, t) })
                    val messages = mutableListOf(
                        buildJsonObject {
                            put("role", "system")
                            put("content", "你是一个助手的分身，只负责下面这一件子任务。用工具查清楚后，给出简洁但完整的结果：" +
                                "要点、关键数字和事实，需要时附来源链接 [编号](链接)。不要寒暄，不要做子任务以外的事。\n" + folder)
                        },
                        buildJsonObject { put("role", "user"); put("content", "子任务「$title」：$what") },
                    )
                    try {
                        agent.run(
                            messages = messages,
                            tools = tools,
                            maxRounds = { 8 },
                            execute = { name, a -> if (name in SUB_TOOLS) execute(name, a) else "这个工具子任务不能用" },
                            onUsage = { ev -> host.store.addUsage(ep, ev.promptTokens, ev.cacheHit, ev.completionTokens) },
                        )
                        "## $title\n" + agent.lastRound.trim().ifBlank { agent.content.toString().trim() }.ifBlank { "（没有结果）" }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "## $title\n（这件没做成：${e.message ?: "出错了"}）"
                    }
                }
            }.awaitAll()
        }
        results.joinToString("\n\n") to SearchRecord("分头做：" + tasks.joinToString("、") { it.first })
    }
}
