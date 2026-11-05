package com.jack.ai.data

import kotlinx.serialization.Serializable

/** 思路来自 deepseek-ai/deepseek-harness packages/goal/goal/，MIT。每个对话至多一个，连同对话序列化。 */
@Serializable
data class Goal(
    val objective: String,
    val criteria: List<String>,
    val status: String = "active",
    val progress: List<String> = emptyList(),
    val autoRounds: Int = 0,
    val id: String = newId(),
    /** 这次自动续做以来累计花了多少元（用户点「继续」时清零）：上限按整个目标算，不按每轮各算各的。 */
    val spent: Double = 0.0,
) {
    val label: String get() = labels[status] ?: status
    val canContinue: Boolean get() = status == "active" && autoRounds < 5

    /** 思路来自 deepseek-ai/deepseek-harness packages/goal/goal/，MIT。状态与进展一起保存。 */
    fun update(status: String = this.status, note: String = ""): Goal {
        require(status in labels) { "状态只能是 active、paused、blocked、complete" }
        return copy(status = status, progress = if (note.isBlank()) progress else progress + note)
    }

    companion object {
        val labels = mapOf("active" to "进行中", "paused" to "暂停", "blocked" to "受阻", "complete" to "完成")
        fun budgetReached(limit: Double, spent: Double) = limit > 0 && spent >= limit
    }
}
