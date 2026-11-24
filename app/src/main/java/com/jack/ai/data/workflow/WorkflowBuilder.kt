package com.jack.ai.data.workflow

import android.content.Context
import com.jack.ai.data.Conversation
import com.jack.ai.data.Settings
import com.jack.ai.data.Store
import com.jack.ai.data.allSkills
import com.jack.ai.data.endpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 工作流里能调的工具：名字、一句说明、参数（名字 → 类型、说明、必填）。 */
data class ToolInfo(val name: String, val description: String, val params: List<ParamInfo>)
data class ParamInfo(val name: String, val type: String, val description: String, val required: Boolean, val options: List<String>)

object WorkflowBuilder {
    /** 这些是聊天里才有意义的（问用户、结束对话、写计划……），工作流里不给选。 */
    private val SKIP = setOf("update_plan", "delegate_tasks", "end_conversation", "ask_user", "self_check", "use_skill", "save_skill", "work_note",
        "show_draft", "workflow", "test_pack", "install_pack")

    /** 现在开着的技能带的工具（工具包的也在里面）。 */
    fun tools(settings: Settings): List<ToolInfo> {
        val defs = com.jack.ai.chat.PromptBuilder.toolSet(settings, settings.allSkills().filter { it.enabled }, Conversation(), null).orEmpty()
        return defs.mapNotNull { d ->
            val f = (d as? JsonObject)?.get("function") as? JsonObject ?: return@mapNotNull null
            val name = (f["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            if (name in SKIP) return@mapNotNull null
            val p = f["parameters"] as? JsonObject
            val req = (p?.get("required") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty().toSet()
            val props = (p?.get("properties") as? JsonObject).orEmpty().map { (k, v) ->
                val o = v as? JsonObject
                ParamInfo(k, (o?.get("type") as? JsonPrimitive)?.content ?: "string", (o?.get("description") as? JsonPrimitive)?.content.orEmpty(), k in req,
                    (o?.get("enum") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty())
            }
            ToolInfo(name, (f["description"] as? JsonPrimitive)?.content.orEmpty(), props)
        }
    }

    /**
     * 让模型按一句话搭工作流（或者改现在这个）。不对的地方交回给它改一次；还不对就把问题报给用户。
     * 用主模型（搭得好一点），钱记在统计页「工作流 · 搭建」。
     */
    suspend fun build(ctx: Context, request: String, current: Workflow?): Result<Workflow> = withContext(Dispatchers.IO) {
        runCatching {
            val store = Store(ctx)
            val settings = store.loadSettings()
            val ep = settings.endpoint() ?: error("还没有设置能用的模型")
            val client = com.jack.ai.api.DeepSeekClient(store.usage("工作流 · 搭建"))
            val catalog = tools(settings).joinToString("\n") { t ->
                "- ${t.name}: ${t.description.take(120)}" + if (t.params.isNotEmpty()) "（参数：" + t.params.joinToString("，") { it.name + if (it.required) "*" else "" } + "）" else ""
            }
            val system = com.jack.ai.tools.WORKFLOW_HELP + "\n\n能调的工具（tool 步骤只能用这些）：\n" + catalog +
                "\n\n只输出工作流的 JSON，不要别的文字。步骤 id 用 s1、s2……；能不用模型就不用（用 tool、extract、if 这些），用了 ai 就写清楚要它输出什么。"
            val ask = if (current != null) "现在的工作流：\n" + WorkflowFormat.encode(current) + "\n\n按这个要求改，输出改好的整个工作流（id 保持不变）：\n$request"
                else "按这个要求搭一个工作流：\n$request"
            val msgs = mutableListOf(
                buildJsonObject { put("role", "system"); put("content", system) },
                buildJsonObject { put("role", "user"); put("content", ask) },
            )
            var problems: List<String> = emptyList()
            repeat(2) { round ->
                val raw = client.complete(ep, msgs, maxTokens = 6000, jsonMode = true)
                val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val r = WorkflowFormat.parse(text)
                r.getOrNull()?.let { w ->
                    return@runCatching w.copy(id = current?.id ?: w.id, by = current?.by ?: "kavi", enabled = current?.enabled ?: true)
                }
                problems = (r.exceptionOrNull() as? WorkflowError)?.problems ?: listOf(r.exceptionOrNull()?.message.orEmpty())
                if (round == 0) {
                    msgs += buildJsonObject { put("role", "assistant"); put("content", raw.take(20_000)) }
                    msgs += buildJsonObject { put("role", "user"); put("content", "有这些问题，改好再输出整个 JSON：\n" + problems.joinToString("\n") { "- $it" }) }
                }
            }
            throw WorkflowError(problems)
        }
    }
}
