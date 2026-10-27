package com.jack.ai.tools

import com.jack.ai.api.DeepSeekClient
import com.jack.ai.chat.Agent
import com.jack.ai.chat.PromptBuilder
import com.jack.ai.data.SearchRecord
import com.jack.ai.data.ThinkLevel
import com.jack.ai.data.backgroundEndpoint
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 分身可以查资料、计算和制作文件；仍按主任务已启用的技能提供工具。
 * 用户的私人东西（聊天记录、表格、邮件、日历、Notion）不给：分身可能用的是别家的免费模型。
 */
private val SUB_TOOLS = setOf(
    "web_search", "read_webpage", "read_file", "write_file", "edit_file", "facts", "query_trains",
    "run_js", "run_python", "create_document", "create_excel", "create_ppt", "create_chart", "create_diagram", "create_app",
)

/**
 * 分头并行（Work）：把几件彼此独立的子任务同时派给“分身”去做，各自查资料、读文件，
 * 做完把结果汇总回来。分身用便宜的后台模型，每个最多 30 次工具。
 */
internal suspend fun ToolSession.delegateTasks(args: JsonObject): String {
    val tasks = (args["tasks"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val title = o["title"].text().trim()
        val what = o["instructions"].text().trim()
        if (title.isBlank() || what.isBlank()) null else title to what
    }.take(5)
    if (tasks.isEmpty()) return "没有子任务"
    // 自由时间里只用免费模型（分身的花费不走它的预算，用付费的就等于白花用户的钱）
    val fallback = if (freeBudget != null) null else settings.backgroundEndpoint()
    val pool = com.jack.ai.data.FreePool.load(app)
    if (freeBudget != null && com.jack.ai.data.freeCandidates(pool, settings, 0).isEmpty()) return "现在没有免费模型可用（额度用完了或还没探测到），这些事自己做就好。"
    return runTool("分头做 ${tasks.size} 件事") {
        val client = DeepSeekClient()
        // 池里的硅基流动模型探测时已经按价格页筛过是免费的，这里不用每次再抓一遍网页
        fun getCandidates(state: com.jack.ai.data.FreePoolState, index: Int) = com.jack.ai.data.freeCandidates(state, settings, index)
        val folder = ContextNote.files(wsDir)
        val tools = JsonArray(PromptBuilder.toolSet(settings, skills, conv, project, unlocked).orEmpty().filter { definition ->
            val fn = (definition as? JsonObject)?.get("function") as? JsonObject
            (fn?.get("name") as? JsonPrimitive)?.content in SUB_TOOLS
        })
        val results = coroutineScope {
            tasks.mapIndexed { index, (title, what) ->
                async {
                    val candidates = getCandidates(pool, index)
                    if (candidates.isEmpty() && fallback == null) return@async "## $title\n（没有可用的模型）"
                    val attempted = mutableSetOf<String>()
                    var ep = com.jack.ai.data.chooseFreeOrFallback(candidates, fallback)!!
                    val agent = Agent({ msgs, t ->
                        kotlinx.coroutines.flow.flow {
                            while (true) {
                                try {
                                    client.stream(ep, msgs, ThinkLevel.OFF, 1.0f, t).collect { emit(it) }
                                    break
                                } catch (e: com.jack.ai.api.ApiException) {
                                    if (e.serviceCode != "AllocationQuota.FreeTierOnly" || ep.key in attempted || candidates.none { it.key == ep.key }) throw e
                                    com.jack.ai.data.FreePool.exhaust(app, ep)
                                    attempted += ep.key
                                    val live = getCandidates(com.jack.ai.data.FreePool.load(app), index)
                                    ep = com.jack.ai.data.chooseFreeOrFallback(live.filter { it.key !in attempted }, fallback?.takeIf { it.key !in attempted }) ?: throw e
                                }
                            }
                        }
                    })
                    val messages = mutableListOf(
                        buildJsonObject {
                            put("role", "system")
                            put("content", "你是一个助手的分身，只负责下面这一件子任务。按任务需要查资料、计算或制作文件，给出简洁完整的结果，需要时附来源链接。" +
                                "只处理分配给你的事，与其他分身共用对话文件夹；新文件用不同名称，修改现有文件时避免冲突。\n" + folder)
                        },
                        buildJsonObject { put("role", "user"); put("content", "子任务「$title」：$what") },
                    )
                    try {
                        agent.run(
                            messages = messages,
                            tools = tools,
                            maxRounds = { 30 },
                            maxToolCalls = { 30 },
                            execute = { name, a ->
                                val action = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(a) as? JsonObject }.getOrNull()
                                if (name !in SUB_TOOLS || (name == "facts" && action?.get("action").text() != "recall")) "这个操作子任务不能用"
                                else execute(name, a)
                            },
                            onUsage = { ev -> host.store.addUsage(ep, ev.promptTokens, ev.cacheHit, ev.completionTokens, label = "子 agent「${title.take(12)}」") },
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
