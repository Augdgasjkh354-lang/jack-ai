package com.jack.ai.data

import java.io.File

/** 在文件夹的代码和文字文件里按关键词（或正则）搜，返回「文件:行号: 那一行」。 */
object CodeSearch {
    const val MAX_HITS = 80

    fun search(dir: File, query: String, regex: Boolean = false, onlyExt: String = ""): String {
        if (query.isBlank()) return "要搜的内容是空的"
        val pattern = if (regex) runCatching { Regex(query) }.getOrElse { return "正则写错了：${it.message}" }
            else Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
        val ext = onlyExt.removePrefix(".").lowercase()
        val files = Workspace.files(dir).filter { CodeHistory.tracked(it) && (ext.isEmpty() || it.extension.lowercase() == ext) }.sortedBy { it.name }
        val hits = mutableListOf<String>()
        var total = 0
        for (f in files) {
            val lines = runCatching { f.readLines() }.getOrNull() ?: continue
            lines.forEachIndexed { i, line ->
                if (pattern.containsMatchIn(line)) {
                    total++
                    if (hits.size < MAX_HITS) hits += "${f.name}:${i + 1}: ${line.trim().take(200)}"
                }
            }
        }
        if (hits.isEmpty()) return "在 ${files.size} 个文件里没搜到「$query」。"
        return "搜到 $total 处" + (if (total > hits.size) "（只列前 ${hits.size} 处，写得再具体些）" else "") + "：\n" + hits.joinToString("\n")
    }
}
