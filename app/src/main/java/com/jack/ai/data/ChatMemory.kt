package com.jack.ai.data

import android.content.Context
import java.io.File

/**
 * 对话记忆和项目记忆：存成对话文件夹里的 Markdown 文件，每条一行「- …」。
 * 用户在「文件夹」里能看到、打开；AI 能用 read_file / edit_file 改；压缩只压聊天记录，这些文件每次都完整带上。
 * 普通对话：文件夹里的「记忆.md」。项目里的对话共用项目文件夹：「项目记忆.md」全项目共用，
 * 每个对话自己的是「对话记忆-编号.md」。
 */
object ChatMemory {
    const val CHAT_FILE = "记忆.md"
    const val PROJECT_FILE = "项目记忆.md"

    fun chatFileName(conv: Conversation): String =
        if (conv.projectId == null) CHAT_FILE else "对话记忆-${conv.id.take(6)}.md"

    fun chatFile(ctx: Context, conv: Conversation): File =
        File(Workspace.dir(ctx, Workspace.key(conv)), chatFileName(conv))

    fun projectFile(ctx: Context, projectId: String): File =
        File(Workspace.dir(ctx, "p_$projectId"), PROJECT_FILE)

    fun read(file: File): String = runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")

    /** 文件里的每一条（「- 」开头的行）。用户手写的其他格式的行也算一条。 */
    fun items(text: String): List<String> = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { it.removePrefix("- ").removePrefix("* ").trim() }
        .filter { it.isNotEmpty() }

    fun render(title: String, items: List<String>): String =
        if (items.isEmpty()) "" else "# $title\n\n" + items.joinToString("\n") { "- $it" } + "\n"

    fun titleOf(file: File) = if (file.name == PROJECT_FILE) "项目记忆" else "本对话记忆"

    /** 加一条；已经有一模一样的就不重复加。返回 false 表示没加。 */
    fun add(file: File, item: String): Boolean {
        val text = item.trim().removePrefix("- ").trim()
        if (text.isEmpty()) return false
        val list = items(read(file))
        if (list.any { it == text }) return false
        write(file, list + text)
        return true
    }

    fun write(file: File, items: List<String>) {
        if (items.isEmpty()) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        file.writeText(render(titleOf(file), items))
    }
}
