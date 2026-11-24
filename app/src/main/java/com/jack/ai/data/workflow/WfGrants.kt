package com.jack.ai.data.workflow

import kotlinx.serialization.json.JsonObject

/**
 * 保存时一次授权：工作流里会往外做、平时要问用户的那几步，保存时列给用户看，同意了记成 [WfGrant]。
 * 后台跑到那一步时，范围（收件人、App、仓库……）和当时同意的一样才放行；改了参数、换了收件人就要重新同意。
 * 付款、转账、输密码、银行这类一律不放行，授权也不行。
 */
object WfGrants {
    /** 会问用户的工具 → 决定「范围」的那几个参数。 */
    private val ASKING = mapOf(
        "send_email" to listOf("to", "cc"),
        "manage_emails" to listOf("action"),
        "phone" to listOf("app", "action"),
        "browser" to listOf("action", "url"),
        "github" to listOf("action", "repo"),
        "notion_write" to listOf("action"),
        "delete_calendar_event" to emptyList(),
        "phone_files" to listOf("action"),
        "run_shell" to emptyList(),
        "edit_table" to listOf("action", "table"),
        "delete_file" to listOf("names", "name"),
    )

    /** 永远不放行的：确认框的标题或正文里有这些。 */
    val NEVER = Regex("付款|支付|转账|收款|密码|银行|验证码|充值|提现")

    fun asks(tool: String) = tool in ASKING || tool.startsWith("mcp_")

    data class Need(val stepId: String, val tool: String, val scope: String, val label: String)

    fun scopeOf(tool: String, args: JsonObject): String =
        ASKING[tool].orEmpty().mapNotNull { k -> args[k]?.let { "$k=" + WfTemplate.text(it) } }.joinToString("; ")

    /** 给人看的：这一步会做什么。 */
    fun label(tool: String, args: JsonObject): String {
        fun a(k: String) = args[k]?.let { WfTemplate.text(it) }.orEmpty()
        val dyn = if (ASKING[tool].orEmpty().any { "{{" in a(it) }) "（每次跑时由前面的结果决定）" else ""
        return when (tool) {
            "send_email" -> "给 ${a("to").ifBlank { "（没写收件人）" }} 发邮件"
            "manage_emails" -> "整理邮件：${a("action")}"
            "phone" -> "在手机上操作" + (if (a("app").isNotBlank()) "「${a("app")}」" else "") + (if (a("action").isNotBlank()) "（${a("action")}）" else "")
            "browser" -> "操作浏览器（${a("action")}）" + (if (a("url").isNotBlank()) " " + a("url").take(60) else "")
            "github" -> "GitHub：${a("action")} ${a("repo")}"
            "notion_write" -> "改 Notion（${a("action")}）"
            "delete_calendar_event" -> "删日历里的事"
            "phone_files" -> "手机文件：${a("action")}"
            "run_shell" -> "在 Linux 沙箱里跑命令"
            "edit_table" -> "改表格「${a("table")}」（${a("action")}）"
            "delete_file" -> "删掉工作流文件夹里的「${a("names").ifBlank { a("name") }}」"
            else -> "用连接器工具 $tool"
        } + dyn
    }

    /** 这个工作流里要授权的每一步。 */
    fun needed(w: Workflow): List<Need> {
        val out = mutableListOf<Need>()
        fun walk(l: List<WfStep>): Unit = l.forEach { s ->
            if (s.type == "tool" && asks(s.tool)) out += Need(s.id, s.tool, scopeOf(s.tool, s.args), label(s.tool, s.args))
            walk(s.then); walk(s.otherwise); walk(s.steps)
        }
        walk(w.steps)
        return out
    }

    fun covers(g: WfGrant, n: Need) = g.stepId == n.stepId && g.tool == n.tool && g.scope == n.scope

    /** 还没授权（或者改过、范围变了）的那些。 */
    fun missing(w: Workflow): List<Need> = needed(w).filter { n -> w.grants.none { covers(it, n) } }

    /** 用户同意了：把要授权的都记上；没同意：只留还对得上的旧授权。 */
    fun apply(w: Workflow, approved: Boolean, now: Long = System.currentTimeMillis()): Workflow {
        val need = needed(w)
        val kept = w.grants.filter { g -> need.any { covers(g, it) } }
        if (!approved) return w.copy(grants = kept)
        return w.copy(grants = kept + need.filter { n -> kept.none { covers(it, n) } }.map { WfGrant(it.stepId, it.tool, it.scope, it.label, now) })
    }

    /** 跑到这一步时：有没有对得上的授权。 */
    fun allowed(w: Workflow, step: WfStep): Boolean {
        val n = Need(step.id, step.tool, scopeOf(step.tool, step.args), "")
        return w.grants.any { covers(it, n) }
    }
}
