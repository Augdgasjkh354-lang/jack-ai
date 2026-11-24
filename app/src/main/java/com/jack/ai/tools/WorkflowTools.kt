package com.jack.ai.tools

import com.jack.ai.data.SearchRecord
import com.jack.ai.data.workflow.WfRun
import com.jack.ai.data.workflow.Workflow
import com.jack.ai.data.workflow.WorkflowError
import com.jack.ai.data.workflow.WorkflowFormat
import com.jack.ai.data.workflow.Workflows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 工作流的格式说明（action=help 时给模型，不放进工具说明里，免得每轮都带着）。 */
internal const val WORKFLOW_HELP = """工作流：固定的几步，到点或手动跑，不用每步问模型。用户想「每天/每周自动做 XX」「一键做一串事」、而且步骤固定时用它；每次要临场判断的用 schedule（定时任务）。
格式（JSON，action=save 时放进 workflow）：
{"name": "股票早报", "description": "一句话",
 "trigger": {"type": "schedule", "repeat": "weekdays", "time": "08:00"},
 "limits": {"timeoutMin": 10, "maxCostYuan": 1},
 "deliver": "inbox",
 "steps": [
  {"id": "s1", "type": "tool", "name": "行情", "tool": "pack_stocks__quote", "args": {"codes": "600519,000858"}},
  {"id": "s2", "type": "forEach", "name": "这一只", "list": "{{行情}}", "steps": [
    {"id": "s3", "type": "if", "cond": {"left": "{{这一只.涨跌幅}}", "op": "<", "right": "-3"}, "then": [
      {"id": "s4", "type": "ai", "name": "点评", "prompt": "用一句话点评 {{这一只.名字}} 今天跌了 {{这一只.涨跌幅}}%"},
      {"id": "s5", "type": "notify", "title": "{{这一只.名字}} 跌了 {{这一只.涨跌幅}}%", "body": "{{点评}}"}]}]}]}
- trigger：type 是 manual（手动）或 schedule。schedule 的 repeat：daily、weekdays、weekly（days：1=周一 … 7=周日）、interval（everyMin，最少 15）、once（date：2026-12-01）；time 写 08:00。
- 步骤 type：
  tool 调工具（tool 是工具名，你能调的任何工具，包括 pack_ 开头的工具包工具；args 里能写 {{变量}}）；
  ai 让模型做（prompt，可选 system；json=true 要它按 JSON 回，后面好取字段）；
  notify 发通知（title、body）；message 发到收件箱（text）；file 存成文件（file 文件名、text 内容）；
  code 跑 JavaScript（vars 是所有变量，return 结果；只能算，不能联网）；set 存一个值（value）；
  extract 取出（from，和 path 如 [0].price 或 regex 取第一组）；
  if（cond、then、else）；forEach（list、steps；name 是「这一项」的变量名，还有 {{序号}}）；wait（seconds）；stop（reason，提前结束）。
- 每一步的结果存成变量，名字是 name（没写就用 id）。引用写 {{名字}}、{{名字.字段}}、{{名字[0].字段}}、{{名字.length}}；自带 {{今天}}、{{现在}}、{{输入}}。整格只写一个 {{…}} 时保留原类型（列表还是列表）。
- cond 的 op：== != > >= < <=（数字比大小，带 % 也行）、contains、notContains、empty、notEmpty、matches（正则）；多条用 {"all": [...]} 或 {"any": [...]}。
- 出错：每步可写 onError：stop（默认，停下）、skip（跳过接着做）、retry（加 retries 次数，最多 5）。
- deliver：结果送哪，inbox（默认，收件箱）、notify、none；自己写了 message / notify 步骤的就不另外送。
- 花钱上限默认一次 ¥1，只算 ai 步骤和调模型的工具；总时限默认 10 分钟。
- 后台跑时，要用户确认的事（发邮件、发消息、改东西）现在一律不做，那一步会报错：先只用查、算、通知、存文件、发到收件箱这些。
做法：先想清楚每一步，工具名和参数要对（工具包的工具名是 pack_工具包id__工具名）。save 会给用户看一遍再存；存好用 run 试跑一次，看每一步的结果对不对，不对就改了再 save（同一个 id 就是更新）。"""

/** 工作流：看、存（用户确认）、跑、看运行记录、开关、删。 */
internal suspend fun ToolSession.workflow(args: JsonObject): String {
    val action = args["action"].text().ifBlank { "list" }
    val key = args["id"].text().ifBlank { args["name"].text() }
    return when (action) {
        "help" -> WORKFLOW_HELP
        "list" -> runTool("看工作流") {
            val all = withContext(Dispatchers.IO) { Workflows.load(app) }
            (if (all.isEmpty()) "还没有工作流。" else all.joinToString("\n") { w ->
                val last = Workflows.runs(app, w.id).firstOrNull()
                "- ${w.name}（id ${w.id}）：${WorkflowFormat.describe(w.trigger)}，${WorkflowFormat.count(w.steps)} 步" +
                    (if (!w.enabled) "，关着" else "") + (last?.let { "，上次 ${statusLabel(it)}" } ?: "")
            }) + "\n（格式说明：action=help）" to SearchRecord("看工作流")
        }
        "show" -> runTool("看工作流：$key") {
            val w = withContext(Dispatchers.IO) { Workflows.find(app, key) } ?: error("没有「$key」这个工作流")
            WorkflowFormat.encode(w) to SearchRecord("看工作流：${w.name}")
        }
        "save" -> runTool("存工作流") {
            val raw = args["workflow"]
            val text = when (raw) { is JsonObject -> raw.toString(); is JsonPrimitive -> raw.content; else -> error("要在 workflow 里给出工作流的 JSON（格式见 action=help）") }
            val parsed = WorkflowFormat.parse(text).getOrElse { e ->
                error("工作流有这些问题，改好再存：\n" + ((e as? WorkflowError)?.problems ?: listOf(e.message.orEmpty())).joinToString("\n") { "- $it" })
            }
            val old = withContext(Dispatchers.IO) { Workflows.get(app, parsed.id) }
            val w = parsed.copy(by = old?.by ?: "kavi", enabled = old?.enabled ?: parsed.enabled, lastFiredAt = old?.lastFiredAt ?: 0)
            if (!approve(if (old != null) "更新工作流「${w.name}」？" else "存下工作流「${w.name}」？", outline(w), if (old != null) "更新" else "存下")) {
                return@runTool "用户没有同意，没存。问问哪里要改。" to SearchRecord("工作流「${w.name}」：用户没同意")
            }
            val saved = withContext(Dispatchers.IO) { Workflows.save(app, w) }
            val next = WorkflowFormat.nextAt(saved, System.currentTimeMillis())?.let { "，下次 " + java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(it)) }.orEmpty()
            "${if (old != null) "已更新" else "已存好"}「${saved.name}」（id ${saved.id}，${WorkflowFormat.describe(saved.trigger)}$next）。用 action=run 试跑一次，看看每一步对不对。" to
                SearchRecord("${if (old != null) "更新" else "存好"}工作流「${saved.name}」")
        }
        "run" -> {
            val w = withContext(Dispatchers.IO) { Workflows.find(app, key) } ?: return "没有「$key」这个工作流。用 action=list 看有哪些。"
            runTool("跑工作流：${w.name}") {
                val input = args["input"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                // 聊天里跑：工具走这个对话的那一套（要确认的照样问用户）
                val r = Workflows.run(app, w, "chat", input, callTool = { n, a ->
                    val out = perform(n, a.toString())
                    if (out.status != ToolStatus.OK) error(out.text.lineSequence().firstOrNull().orEmpty().take(300))
                    out.text
                })
                report(r) to SearchRecord("跑工作流：${w.name}（${statusLabel(r)}）")
            }
        }
        "runs" -> runTool("看运行记录：$key") {
            val w = withContext(Dispatchers.IO) { Workflows.find(app, key) } ?: error("没有「$key」这个工作流")
            val runs = withContext(Dispatchers.IO) { Workflows.runs(app, w.id) }
            (runs.firstOrNull()?.let { "最近一次：\n" + report(it) + "\n\n" }.orEmpty() +
                "最近几次：\n" + runs.take(10).joinToString("\n") { r -> "- " + time(r.startedAt) + " " + Workflows.triggerLabel(r.trigger) + "，" + statusLabel(r) }
                ).ifBlank { "还没跑过" } to SearchRecord("看运行记录：${w.name}")
        }
        "enable", "disable" -> runTool("${if (action == "enable") "打开" else "关掉"}工作流：$key") {
            val w = withContext(Dispatchers.IO) { Workflows.find(app, key) } ?: error("没有「$key」这个工作流")
            withContext(Dispatchers.IO) { Workflows.update(app, w.id) { it.copy(enabled = action == "enable") } }
            "已${if (action == "enable") "打开" else "关掉"}「${w.name}」" to SearchRecord("${if (action == "enable") "打开" else "关掉"}工作流：${w.name}")
        }
        "delete" -> runTool("删工作流：$key") {
            val w = withContext(Dispatchers.IO) { Workflows.find(app, key) } ?: error("没有「$key」这个工作流")
            if (!approve("删掉工作流「${w.name}」？", "运行记录也一起删掉。", "删除")) return@runTool "用户没同意，没删。" to SearchRecord("删工作流：已取消")
            withContext(Dispatchers.IO) { Workflows.delete(app, w.id) }
            "已删掉「${w.name}」" to SearchRecord("删工作流：${w.name}")
        }
        else -> "action 只能是 help、list、show、save、run、runs、enable、disable、delete"
    }
}

/** 给用户确认用：什么时候跑、每一步做什么。 */
internal fun outline(w: Workflow): String = buildString {
    if (w.description.isNotBlank()) append(w.description).append("\n\n")
    append("什么时候开始：").append(WorkflowFormat.describe(w.trigger)).append("\n")
    append("花钱上限：每次 ¥").append(w.limits.maxCostYuan).append("，最多跑 ").append(w.limits.timeoutMin).append(" 分钟\n\n")
    fun walk(list: List<com.jack.ai.data.workflow.WfStep>, indent: String) {
        list.forEach { s ->
            append(indent).append("· ").append(com.jack.ai.data.workflow.stepSummary(s)).append("\n")
            if (s.type == "if") { if (s.then.isNotEmpty()) { append("$indent  是：\n"); walk(s.then, "$indent    ") }; if (s.otherwise.isNotEmpty()) { append("$indent  否：\n"); walk(s.otherwise, "$indent    ") } }
            walk(s.steps, "$indent  ")
        }
    }
    walk(w.steps, "")
}.trim()

private fun statusLabel(r: WfRun) = when (r.status) {
    "ok" -> "跑完了"; "stopped" -> "停下了（${r.error.take(40)}）"; "limit" -> "碰到上限（${r.error.take(40)}）"; "failed" -> "出错（${r.error.take(60)}）"; else -> r.status
}

private fun time(t: Long) = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(t))

/** 一次运行的经过：每一步拿到了什么。 */
private fun report(r: WfRun): String = buildString {
    append(statusLabel(r)).append("，用了 ").append((r.endedAt - r.startedAt) / 1000).append(" 秒")
    if (r.costYuan > 0) append("，花了 ¥").append(String.format(java.util.Locale.US, "%.3f", r.costYuan))
    append("\n")
    r.steps.take(60).forEach { s ->
        append(if (!s.ok) (if (s.skipped) "↷ " else "✗ ") else "✓ ").append(s.name)
        if (s.at.isNotEmpty()) append("（${s.at}）")
        val v = if (s.ok) s.output else s.error
        if (v.isNotBlank()) append("：").append(v.replace("\n", " ").take(300))
        append("\n")
    }
    if (r.steps.size > 60) append("…（还有 ${r.steps.size - 60} 步）\n")
    if (r.output.isNotBlank()) append("\n结果：").append(r.output.take(2000))
}.trim()
