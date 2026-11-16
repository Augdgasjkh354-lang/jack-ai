package com.jack.ai.data

import java.io.File

/**
 * 文件库：把所有对话（和项目）文件夹里的文件放在一处看，按类型筛、按名字搜，点一下回到它所在的对话。
 * 只读文件夹，不另存一份清单，删了对话文件夹里的文件这里自然就没了。
 * 思路参考 Mika 的跨会话文件库（Apache-2.0），Kavi 自己的实现。
 */
object FileLibrary {
    enum class Kind(val label: String) { IMAGE("图片"), DOC("文档"), SHEET("表格"), MEDIA("音视频"), CODE("代码"), OTHER("其他") }

    /** 一个文件：在哪个对话（或项目）的文件夹里。convId 是能跳回去的对话；项目文件夹跳到项目里最近的对话。 */
    data class Entry(val file: File, val kind: Kind, val place: String, val convId: String?, val modified: Long)

    private val exts = mapOf(
        Kind.IMAGE to setOf("png", "jpg", "jpeg", "gif", "webp", "heic", "bmp", "svg"),
        Kind.DOC to setOf("pdf", "doc", "docx", "ppt", "pptx", "txt", "md", "rtf", "epub", "html", "htm"),
        Kind.SHEET to setOf("xls", "xlsx", "csv", "tsv", "ods"),
        Kind.MEDIA to setOf("mp3", "m4a", "wav", "aac", "flac", "ogg", "opus", "amr", "mp4", "mov", "mkv", "webm", "avi", "3gp"),
        Kind.CODE to setOf("py", "js", "ts", "kt", "java", "json", "xml", "yaml", "yml", "sh", "c", "cpp", "go", "rs", "sql", "css"),
    )

    fun kindOf(name: String): Kind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return exts.entries.firstOrNull { ext in it.value }?.key ?: Kind.OTHER
    }

    /** 扫一遍 workspaces 下面每个文件夹（只看第一层的文件，隐藏的 .src、.repos 这类不算），新的在前。 */
    fun scan(root: File, conversations: List<Conversation>, projects: List<Project>): List<Entry> {
        val convs = conversations.associateBy { it.id }
        return root.listFiles()?.filter { it.isDirectory }.orEmpty().flatMap { dir ->
            val key = dir.name
            val (place, convId) = if (key.startsWith("p_")) {
                val pid = key.removePrefix("p_")
                val name = projects.firstOrNull { it.id == pid }?.name ?: return@flatMap emptyList()
                "项目「$name」" to conversations.filter { it.projectId == pid }.maxByOrNull { it.updatedAt }?.id
            } else {
                val c = convs[key] ?: return@flatMap emptyList()
                "「${c.title}」" to c.id
            }
            Workspace.files(dir).map { f -> Entry(f, kindOf(f.name), place, convId, f.lastModified()) }
        }.sortedByDescending { it.modified }
    }

    /** 按类型和名字筛（kind 为 null 是全部）。 */
    fun filter(list: List<Entry>, kind: Kind?, query: String): List<Entry> {
        val q = query.trim()
        return list.filter { (kind == null || it.kind == kind) && (q.isEmpty() || it.file.name.contains(q, true) || it.place.contains(q, true)) }
    }
}
