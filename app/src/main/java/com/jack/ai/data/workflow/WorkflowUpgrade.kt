package com.jack.ai.data.workflow

import com.jack.ai.data.Routine

/**
 * 把一个定时任务「升级成工作流」：时间照搬，做的事拆成步骤，之后可以在卡片编辑器里接着加步骤、加分叉。
 * 提醒 → 发通知；定时任务 → 让 Kavi 做 + 发到收件箱；监控 → 监控触发 + 让 Kavi 判断（按 JSON 回）+ 不满足就停下。
 * 注意：「让 Kavi 做」只是问一次模型，不会自己上网、调工具；原来要上网查的，升级后在前面加「调用工具」的步骤。
 */
object WorkflowUpgrade {
    fun from(r: Routine): Workflow {
        val hm = String.format(java.util.Locale.US, "%02d:%02d", r.hour, r.minute)
        val trigger = when {
            r.kind == "watch" -> WfTrigger("schedule", "watch", everyMin = r.everyMin.coerceAtLeast(15))
            r.repeat == "weekly" -> {
                val c = java.util.Calendar.getInstance().apply {
                    runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(r.date) }.getOrNull()?.let { time = it }
                }
                WfTrigger("schedule", "weekly", hm, days = listOf((c.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 + 1))
            }
            r.repeat == "none" -> WfTrigger("schedule", "once", hm, date = r.date)
            r.repeat in setOf("daily", "weekdays") -> WfTrigger("schedule", r.repeat, hm)
            else -> WfTrigger("schedule", "daily", hm)
        }
        val steps = when (r.kind) {
            "reminder" -> listOf(WfStep(id = "s1", type = "notify", title = "⏰ 提醒", body = r.title))
            "watch" -> listOf(
                WfStep(id = "s1", type = "ai", name = "判断", json = true,
                    prompt = "要盯的事：${r.prompt.ifBlank { r.title }}\n今天是 {{今天}} {{现在}}。判断现在满足了没有，按 JSON 回：{\"met\": true 或 false, \"info\": \"一句话说明\"}"),
                WfStep(id = "s2", type = "if", cond = WfCond("{{判断.met}}", "!=", "true"), then = listOf(WfStep(id = "s3", type = "stop", reason = "还没满足：{{判断.info}}"))),
                WfStep(id = "s4", type = "notify", title = "⚙ ${r.title}", body = "{{判断.info}}"),
            ) + (if (r.then.isNotBlank()) listOf(WfStep(id = "s5", type = "ai", name = "接着做", prompt = r.then + "\n\n刚才查到：{{判断.info}}"),
                WfStep(id = "s6", type = "message", text = "{{接着做}}")) else emptyList())
            else -> listOf(
                WfStep(id = "s1", type = "ai", name = "结果", prompt = r.prompt.ifBlank { r.title } + "\n\n（今天是 {{今天}}）"),
                WfStep(id = "s2", type = "message", text = "{{结果}}"),
            )
        }
        return Workflow(name = r.title.take(40).ifBlank { "定时任务" }, description = "从定时任务「${r.title}」升级来的", trigger = trigger, steps = steps)
    }
}
