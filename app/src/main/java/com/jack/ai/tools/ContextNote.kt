package com.jack.ai.tools

import android.content.Context
import com.jack.ai.data.Conversation
import com.jack.ai.data.SkillIds
import com.jack.ai.data.Store
import com.jack.ai.data.Tables
import com.jack.ai.data.Workspace
import com.jack.ai.data.describe
import java.io.File

/**
 * 每轮加在最后一条用户消息前面的清单：对话文件夹里的文件、表格、提醒和任务。
 * 模型一开始就知道有什么，不用专门调工具去列；放在最后一条消息上，不影响前面的缓存。
 */
object ContextNote {
    private const val MAX_ITEMS = 30

    fun build(ctx: Context, store: Store, conv: Conversation, skillIds: Set<String>): String = buildString {
        // 思路来自 deepseek-ai/deepseek-harness packages/goal/goal/，MIT。动态目标放本轮消息，固定提示词缓存不变。
        store.loadConversations().firstOrNull { it.id == conv.id }?.goal?.let { g ->
            append("【Persistent goal】").append(com.jack.ai.data.Goals.prompt(g) + "\n状态：${g.status}；最新进展：${g.progress.lastOrNull().orEmpty()}").append("\n")
        }
        append(files(Workspace.dir(ctx, Workspace.key(conv))))
        // 能上网时：以前碰到过问题的网站，开工就知道该换来源（放在最后一条消息上，不影响缓存）
        if (conv.work || com.jack.ai.data.SkillIds.WEB in skillIds) append(com.jack.ai.data.Persona.fill(com.jack.ai.data.browser.SiteNotes.note(com.jack.ai.data.browser.SiteNotes.load(ctx)), store.loadSettings()))
        // 收件箱要知道有哪些项目，才能归到对的项目里
        if (conv.id == com.jack.ai.data.INBOX_ID) {
            // 隐私项目不列出来：收件箱是公共的入口，不往隐私项目里归档
            val names = store.loadProjects().filterNot { it.isolated }.map { it.name }
            append("【Projects】").append(if (names.isEmpty()) "none yet" else names.take(MAX_ITEMS).joinToString(", ")).append("\n")
        }
        // 我的模板：有就列出名字，模型才知道「用我的模板」指哪个
        if (setOf(SkillIds.WORD, SkillIds.PPT, SkillIds.EXCEL).any { it in skillIds }) {
            val templates = com.jack.ai.data.design.StyleTemplates.load(ctx)
            if (templates.isNotEmpty()) append("【My templates】").append(templates.joinToString("; ") { t ->
                "${t.name} (${t.kind}${if (t.isDefault) ", default" else ""})"
            }).append("\n")
        }
        if (SkillIds.TABLES in skillIds) {
            val tables = Tables.list(ctx)
            append("【Tables】")
            if (tables.isEmpty()) append("none yet")
            else append(tables.take(MAX_ITEMS).joinToString("; ") { "「${it.name}」${it.rows.size} rows: ${it.columns.joinToString(", ")}" })
            if (tables.size > MAX_ITEMS) append("; ...and ${tables.size - MAX_ITEMS} more")
            append("\n")
        }
        if (SkillIds.ROUTINE in skillIds) {
            val list = store.loadRoutines().filter { it.enabled }
            if (list.isNotEmpty()) {
                append("【Reminders and tasks】\n")
                list.take(MAX_ITEMS).forEach { r ->
                    append("id ${r.id.take(8)} | ${when (r.kind) { "reminder" -> "reminder"; "watch" -> "watch"; else -> "task" }} | ${r.describe()} | ${r.title}")
                    // 上一次跑得怎么样：用户问「那个任务怎么没跑」时有据可查
                    com.jack.ai.data.RunLog.lastText(ctx, r.id)?.let { append(" | $it") }
                    append("\n")
                }
                if (list.size > MAX_ITEMS) append("...${list.size - MAX_ITEMS} more not listed; the full list is under 「提醒和任务」 in the side menu\n")
            }
        }
    }

    /**
     * 只发变了的段落：清单每轮都附在用户消息上、存进历史，一样的内容再发一遍是白花钱（这段每次都不中缓存），还把上下文撑大。
     * 段落按「【标题】」分；sent 记着这个对话里每段上次发的样子（标题 → 「哈希@消息 id」）。
     * 那条消息还在（没被压缩、没被改掉）且内容一样的段落不再发，只留一行说明；上次发过、这次没了的说一声没了。
     * 【Still you】这种隔一阵要重新提一次的，超过 [REPEAT_AFTER] 条消息就照发。
     * 返回（这次要发的文字，新的 sent）。
     */
    fun delta(note: String, sent: Map<String, String>, conv: Conversation, msgId: String): Pair<String, Map<String, String>> {
        val live = com.jack.ai.data.memory.Compression.liveMessages(conv)
        val pos = live.withIndex().filter { it.value.contextPrefix != null }.associate { it.value.id to it.index }
        fun stillThere(v: String, header: String): Boolean {
            val i = pos[v.substringAfter('@')] ?: return false
            return header !in REPEAT || live.size - i <= REPEAT_AFTER
        }
        val valid = sent.filter { (h, v) -> stillThere(v, h) }
        val sections = sections(note)
        val out = StringBuilder()
        val same = mutableListOf<String>()
        val now = valid.toMutableMap()
        for ((header, text) in sections) {
            if (header.isEmpty()) { out.append(text); continue }
            val hash = text.hashCode().toString(16)
            if (valid[header]?.substringBefore('@') == hash) { same += header; continue }
            out.append(text)
            now[header] = "$hash@$msgId"
        }
        val gone = valid.keys.filter { h -> h !in REPEAT && sections.none { it.first == h } }
        gone.forEach { now.remove(it) }
        if (gone.isNotEmpty()) out.append(gone.joinToString("") { "$it none now.\n" })
        if (same.isNotEmpty()) out.append("Unchanged since earlier in this conversation, so not repeated: ${same.joinToString(", ")}\n")
        return out.toString() to now
    }

    /** 隔一阵要重新提一次的段落。 */
    private val REPEAT = setOf("【Still you】")
    const val REPEAT_AFTER = 30

    /** 按行首的「【标题】」切段：（标题，整段文字）；第一个标题前面的文字标题为空。 */
    internal fun sections(note: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var header = ""
        val cur = StringBuilder()
        for (line in note.split('\n').let { if (note.endsWith('\n')) it.dropLast(1) else it }) {
            val h = if (line.startsWith("【") && '】' in line) line.substring(0, line.indexOf('】') + 1) else null
            if (h != null) {
                if (cur.isNotEmpty()) out += header to cur.toString()
                header = h; cur.clear()
            }
            cur.append(line).append('\n')
        }
        if (cur.isNotEmpty()) out += header to cur.toString()
        return out
    }

    /** 项目笔记的文件名结尾：「贪吃蛇-笔记.md」。 */
    const val PROJECT_NOTES_SUFFIX = "-笔记.md"

    /** 对话文件夹里的文件，空的就不写。 */
    fun files(dir: File): String {
        val list = Workspace.files(dir)
        if (list.isEmpty()) return ""
        val shown = list.take(MAX_ITEMS).joinToString(", ") { "${it.name} (${ToolSession.sizeText(it.length())})" }
        // 写代码的项目笔记：接着做哪个项目，先读它的笔记
        val notes = list.filter { it.name.endsWith(PROJECT_NOTES_SUFFIX) }.map { it.name }
        return "【Conversation folder】$shown" + (if (list.size > MAX_ITEMS) " (${list.size} in total)" else "") + "\n" +
            (if (notes.isEmpty()) "" else "【Project notes】${notes.joinToString(", ")}: before continuing one of these projects, read its notes; update them before you stop.\n")
    }
}
