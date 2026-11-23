package com.jack.ai.data.memory

import android.content.Context
import java.io.File
import com.jack.ai.data.Conversation
import com.jack.ai.data.SafeFile
import com.jack.ai.data.Workspace

/**
 * 对话记忆和项目记忆：存成对话文件夹里的 Markdown 文件，每条一行「- …」。
 * 用户在「文件夹」里能看到、打开；AI 能用 read_file / edit_file 改；压缩只压聊天记录，这些文件每次都完整带上。
 * 普通对话：文件夹里的「记忆.md」。项目里的对话共用项目文件夹：「项目记忆.md」全项目共用，
 * 每个对话自己的是「对话记忆-编号.md」。
 */
object ChatMemory {
    const val CHAT_FILE = "记忆.md"
    const val PROJECT_FILE = "项目记忆.md"
    const val WORK_FILE = "工作记忆.md"
    const val WORK_DIR = "work"

    fun chatFileName(conv: Conversation): String =
        if (conv.projectId == null) CHAT_FILE else "对话记忆-${conv.id.take(6)}.md"

    fun chatFile(ctx: Context, conv: Conversation): File =
        File(Workspace.dir(ctx, Workspace.key(conv)), chatFileName(conv))

    fun projectFile(ctx: Context, projectId: String): File =
        File(Workspace.dir(ctx, "p_$projectId"), PROJECT_FILE)

    /** 做事经验（以前叫工作记忆，文件名不改）：所有对话共用，记踩过的坑、交付习惯、常用路径。 */
    fun workFile(ctx: Context): File = File(Workspace.dir(ctx, WORK_DIR), WORK_FILE)

    fun read(file: File): String = runCatching { SafeFile.readText(file) }.getOrDefault("")

    /**
     * 项目记忆分几块：设定（背景、人物、约束）、决定（定下来的事）、进度（做到哪了）、待办（还没做的）。
     * 每条前面带「【决定】」这样的标记，文件里按块写成「## 决定」下面一行行（标记不重复写），读回来再按所在的块补上标记。
     * 不带标记的放在「其他」里，原样往返。别的记忆文件不带标记，还是一行行的老样子。
     */
    val SECTIONS = listOf("设定", "决定", "进度", "待办")
    private const val OTHER = "其他"

    /** 这条属于哪一块（【决定】…）；没标记是 null。 */
    fun sectionOf(item: String): String? = SECTIONS.firstOrNull { item.startsWith("【$it】") }

    /** 文件里的每一条（「- 」开头的行）。用户手写的其他格式的行也算一条。「## 决定」这类块标题下的条目补上【决定】标记。 */
    fun items(text: String): List<String> {
        var section: String? = null
        val out = mutableListOf<String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                section = line.trimStart('#').trim().takeIf { it in SECTIONS }
                continue
            }
            val item = line.removePrefix("- ").removePrefix("* ").trim()
            if (item.isEmpty()) continue
            out += if (section != null && sectionOf(item) == null) "【$section】$item" else item
        }
        return out
    }

    fun render(title: String, items: List<String>): String {
        if (items.isEmpty()) return ""
        if (items.none { sectionOf(it) != null }) return "# $title\n\n" + items.joinToString("\n") { "- $it" } + "\n"
        // 分块写：没标记的放最后的「其他」
        val groups = items.groupBy { sectionOf(it) ?: OTHER }
        return "# $title\n" + (SECTIONS + OTHER).mapNotNull { sec ->
            groups[sec]?.let { list -> "\n## $sec\n" + list.joinToString("\n") { "- " + it.removePrefix("【$sec】") } + "\n" }
        }.joinToString("")
    }

    fun titleOf(file: File) = when (file.name) { PROJECT_FILE -> "项目记忆"; WORK_FILE -> "做事经验"; else -> "本对话记忆" }

    private val updating = Any()

    /**
     * 在文件里最新的条目上改并写回，读、改、写在同一把锁里（做事经验是所有对话共用的，几个对话同时回看不会互相盖掉）。
     * transform 返回 null 表示不改。返回写下的条目，没改返回 null。
     */
    fun update(file: File, transform: (List<String>) -> List<String>?): List<String>? = synchronized(updating) {
        val next = transform(items(read(file))) ?: return null
        write(file, next)
        next
    }

    /** 加一条；已经有一模一样的就不重复加。返回 false 表示没加。 */
    fun add(file: File, item: String): Boolean {
        val text = item.trim().removePrefix("- ").trim()
        if (text.isEmpty()) return false
        return update(file) { list -> if (list.any { it == text }) null else list + text } != null
    }

    /**
     * 把 old 认出的那一条换成 new；new 为空就删掉。old 可以是原话的一段，但要能唯一认出一条：
     * 先找一字不差的，没有再找包含它的。认不出或认出好几条都报错，并把候选列出来。
     */
    fun replace(items: List<String>, old: String, new: String): List<String> {
        val key = old.trim().removePrefix("- ").trim()
        if (key.isEmpty()) throw IllegalArgumentException("replace 要填旧记忆的原话")
        val exact = items.indices.filter { items[it] == key }
        val hits = exact.ifEmpty { items.indices.filter { items[it].contains(key, ignoreCase = true) } }
        when {
            hits.isEmpty() -> throw IllegalArgumentException("没找到包含「$key」的记忆。现有的：" +
                items.ifEmpty { listOf("（还没有）") }.joinToString("；"))
            hits.size > 1 -> throw IllegalArgumentException("「$key」对上了 ${hits.size} 条，写得再具体些：" +
                hits.joinToString("；") { items[it] })
        }
        val i = hits.single()
        val text = new.trim().removePrefix("- ").trim()
        return if (text.isEmpty()) items.filterIndexed { j, _ -> j != i } else items.mapIndexed { j, v -> if (j == i) text else v }
    }

    fun write(file: File, items: List<String>) {
        if (items.isEmpty()) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        SafeFile.writeText(file, render(titleOf(file), items))
    }
}
