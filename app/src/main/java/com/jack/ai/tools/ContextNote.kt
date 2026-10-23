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
        append(files(Workspace.dir(ctx, Workspace.key(conv))))
        // 收件箱要知道有哪些项目，才能归到对的项目里
        if (conv.id == com.jack.ai.data.INBOX_ID) {
            // 隐私项目不列出来：收件箱是公共的入口，不往隐私项目里归档
            val names = store.loadProjects().filterNot { it.isolated }.map { it.name }
            append("【项目】").append(if (names.isEmpty()) "还没有项目" else names.take(MAX_ITEMS).joinToString("、")).append("\n")
        }
        if (SkillIds.TABLES in skillIds) {
            val tables = Tables.list(ctx)
            append("【表格】")
            if (tables.isEmpty()) append("还没有")
            else append(tables.take(MAX_ITEMS).joinToString("；") { "「${it.name}」${it.rows.size} 行：${it.columns.joinToString("、")}" })
            if (tables.size > MAX_ITEMS) append("；……还有 ${tables.size - MAX_ITEMS} 张")
            append("\n")
        }
        if (SkillIds.ROUTINE in skillIds) {
            val list = store.loadRoutines().filter { it.enabled }
            if (list.isNotEmpty()) {
                append("【提醒和任务】\n")
                list.take(MAX_ITEMS).forEach { r ->
                    append("编号 ${r.id.take(8)}｜${when (r.kind) { "reminder" -> "提醒"; "watch" -> "监控"; else -> "任务" }}｜${r.describe()}｜${r.title}")
                    // 上一次跑得怎么样：用户问「那个任务怎么没跑」时有据可查
                    com.jack.ai.data.RunLog.lastText(ctx, r.id)?.let { append("｜$it") }
                    append("\n")
                }
                if (list.size > MAX_ITEMS) append("……还有 ${list.size - MAX_ITEMS} 条没列出，在左侧菜单「提醒和任务」里能看全\n")
            }
        }
    }

    /** 对话文件夹里的文件，空的就不写。 */
    fun files(dir: File): String {
        val list = Workspace.files(dir)
        if (list.isEmpty()) return ""
        val shown = list.take(MAX_ITEMS).joinToString("、") { "${it.name}（${ToolSession.sizeText(it.length())}）" }
        return "【对话文件夹】$shown" + (if (list.size > MAX_ITEMS) " 等 ${list.size} 个" else "") + "\n"
    }
}
