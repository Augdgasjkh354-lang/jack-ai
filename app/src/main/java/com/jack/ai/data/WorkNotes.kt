package com.jack.ai.data

import java.io.File

/**
 * 长任务的工作笔记（思路来自 OthmanAdi/planning-with-files：上下文是内存，文件是硬盘，重要的写到硬盘上，MIT）：
 * Kavi 做长任务时把查到的结论（带出处）、做的决定和理由、走不通的路一条条记进对话文件夹的「工作笔记.md」。
 * 对话被压缩、任务被打断后接着做时，先看它，细节就不会丢。
 */
object WorkNotes {
    const val NAME = "工作笔记.md"
    /** 接着做时带上最后这么多字（太长就只留最近的）。 */
    const val RECALL_CHARS = 6000

    private val KINDS = mapOf("finding" to "发现", "decision" to "决定", "dead_end" to "走不通", "todo" to "待办")

    fun line(kind: String, text: String, at: Long = System.currentTimeMillis()): String {
        val t = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(at))
        return "- [$t] 【${KINDS[kind] ?: "发现"}】" + text.trim().replace(Regex("""\s*\n\s*"""), "；") + "\n"
    }

    fun append(dir: File, kind: String, text: String): File {
        val f = File(dir, NAME)
        if (!f.exists()) f.writeText("# 工作笔记\n\n（Kavi 做长任务时记的：查到的结论和出处、做的决定、走不通的路。）\n\n")
        f.appendText(line(kind, text))
        return f
    }

    /** 接着做时带给模型的：没有笔记返回空。 */
    fun recall(dir: File): String {
        val f = File(dir, NAME)
        if (!f.isFile) return ""
        val s = runCatching { f.readText() }.getOrDefault("").trim()
        if (s.isEmpty()) return ""
        val body = if (s.length <= RECALL_CHARS) s else "…（前面的略，要看就 read_file）\n" + s.takeLast(RECALL_CHARS).substringAfter('\n')
        return "\n你的工作笔记（$NAME）：\n$body\n"
    }
}
