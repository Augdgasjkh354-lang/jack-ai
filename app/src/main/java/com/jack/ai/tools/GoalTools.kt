package com.jack.ai.tools

import com.jack.ai.data.Goal
import com.jack.ai.data.Goals
import com.jack.ai.data.SearchRecord
import kotlinx.serialization.json.*

/** 思路来自 deepseek-ai/deepseek-harness packages/goal/tool-goal/，MIT。普通聊天不建目标，只有明确的多步长任务才建。 */
internal suspend fun ToolSession.goalTool(name: String, args: JsonObject): String = runTool("管理任务目标") {
    val goal = when (name) {
        "get_goal" -> Goals.current(app, conv.id)
        "create_goal" -> {
            val objective = args["objective"].text().trim()
            val criteria = (args["criteria"] as? JsonArray).orEmpty().map { it.text().trim() }.filter { it.isNotEmpty() }
            require(objective.isNotBlank() && criteria.isNotEmpty()) { "请提供一句话的 objective 和非空的 criteria 验收标准" }
            Goals.change(app, conv.id, conv) { old ->
                require(old == null) { "本对话已有一个目标，请先 get_goal；要换目标，请用户在顶部清除。" }
                Goal(objective, criteria)
            }
        }
        else -> Goals.change(app, conv.id, conv) { old ->
            requireNotNull(old) { "还没有目标，请先 create_goal" }
            val status = args["status"].text().ifBlank { old.status }
            old.update(status, args["progress"].text()).let { g ->
                if (interactive && status == "active" && old.status != "active") g.copy(autoRounds = 0) else g
            }
        }
    }
    (goal?.let { Json.encodeToString(Goal.serializer(), it) } ?: "本对话没有目标") to SearchRecord("管理任务目标")
}
