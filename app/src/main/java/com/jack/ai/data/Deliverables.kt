package com.jack.ai.data

import kotlinx.serialization.Serializable
import java.io.File

/** 一次 Work 交付里的一个文件：新建、改动还是删除；文字文件带上加了几行、删了几行。 */
@Serializable
data class Delivered(
    val name: String,
    /** new / changed / deleted */
    val kind: String,
    val added: Int = -1,
    val removed: Int = -1,
)

/**
 * 交付清单：回答开始时记下对话文件夹的样子，结束时一比，就知道这一轮交了哪些文件、改了哪些。
 * 不靠模型自己说，靠文件夹本身。
 */
object Deliverables {
    /** 比这大的文字文件只看改没改，不数行。 */
    const val MAX_TEXT = 300_000L
    private val TEXT_EXT = setOf("md", "txt", "csv", "json", "html", "htm", "py", "js", "kt", "java", "xml", "yaml", "yml", "tsv", "log", "css", "sql", "sh")

    class Snap(val modified: Long, val size: Long, val text: String?)

    private fun isText(f: File) = f.extension.lowercase() in TEXT_EXT && f.length() <= MAX_TEXT

    fun snapshot(dir: File): Map<String, Snap> = Workspace.files(dir).associate { f ->
        f.name to Snap(f.lastModified(), f.length(), if (isText(f)) runCatching { f.readText() }.getOrNull() else null)
    }

    fun diff(before: Map<String, Snap>, dir: File): List<Delivered> {
        val now = Workspace.files(dir)
        val out = mutableListOf<Delivered>()
        for (f in now) {
            val old = before[f.name]
            if (old == null) {
                val lines = if (isText(f)) runCatching { f.readText().lines().size }.getOrDefault(-1) else -1
                out += Delivered(f.name, "new", added = lines)
                continue
            }
            if (old.modified == f.lastModified() && old.size == f.length()) continue
            val text = if (isText(f)) runCatching { f.readText() }.getOrNull() else null
            if (text != null && old.text != null) {
                if (text == old.text) continue
                val (a, r) = lineChanges(old.text, text)
                out += Delivered(f.name, "changed", a, r)
            } else out += Delivered(f.name, "changed")
        }
        val names = now.map { it.name }.toSet()
        before.keys.filter { it !in names }.forEach { out += Delivered(it, "deleted") }
        return out
    }

    /** 加了几行、删了几行：按行的多重集合比，不管顺序（够用来看改动大小）。 */
    internal fun lineChanges(old: String, new: String): Pair<Int, Int> {
        val left = HashMap<String, Int>()
        old.lines().forEach { left[it] = (left[it] ?: 0) + 1 }
        var added = 0
        new.lines().forEach { l ->
            val n = left[l] ?: 0
            if (n > 0) left[l] = n - 1 else added++
        }
        return added to left.values.sum()
    }

    /** 一行话：「新建 2 个 · 改动 1 个 · 删除 1 个」。 */
    fun summary(list: List<Delivered>): String = listOfNotNull(
        list.count { it.kind == "new" }.takeIf { it > 0 }?.let { "新建 $it 个" },
        list.count { it.kind == "changed" }.takeIf { it > 0 }?.let { "改动 $it 个" },
        list.count { it.kind == "deleted" }.takeIf { it > 0 }?.let { "删除 $it 个" },
    ).joinToString(" · ")
}
